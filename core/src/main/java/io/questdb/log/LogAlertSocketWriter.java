/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.log;

import io.questdb.VisibleForTesting;
import io.questdb.mp.QueueConsumer;
import io.questdb.mp.RingQueue;
import io.questdb.mp.SCSequence;
import io.questdb.mp.SynchronizedJob;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.std.datetime.microtime.MicrosecondClockImpl;
import io.questdb.std.str.CharSink;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;

import java.io.*;

import static io.questdb.log.TemplateParser.TemplateNode;

public class LogAlertSocketWriter extends SynchronizedJob implements Closeable, LogWriter {

    static final String DEFAULT_ALERT_TPT_FILE = "/alert-manager-tpt.json";
    static final CharSequenceObjHashMap<CharSequence> ALERT_PROPS = TemplateParser.adaptMap(System.getenv());
    private static final String DEFAULT_ENV_VALUE = "GLOBAL";
    private static final String ORG_ID_ENV = "ORGID";
    private static final String NAMESPACE_ENV = "NAMESPACE";
    private static final String CLUSTER_ENV = "CLUSTER_NAME";
    private static final String INSTANCE_ENV = "INSTANCE_NAME";
    private static final String MESSAGE_ENV = "ALERT_MESSAGE";
    private static final String MESSAGE_ENV_VALUE = "${" + MESSAGE_ENV + "}";
    private final int level;
    private final MicrosecondClock clock;
    private final FilesFacade ff;
    private final SCSequence writeSequence;
    private final RingQueue<LogRecordSink> alertsSourceQueue;
    private final TemplateParser alertTemplate = new TemplateParser();
    private HttpLogRecordSink alertSink;
    private LogAlertSocket socket;
    private ObjList<TemplateNode> alertTemplateNodes;
    private int alertTemplateNodesLen;
    private final QueueConsumer<LogRecordSink> alertsProcessor = this::onLogRecord;
    // changed by introspection
    private String defaultAlertHost;
    private String defaultAlertPort;
    private String location;
    private String inBufferSize;
    private String outBufferSize;
    private String alertTargets;
    private String reconnectDelay;

    public LogAlertSocketWriter(RingQueue<LogRecordSink> alertsSrc, SCSequence writeSequence, int level) {
        this(
                FilesFacadeImpl.INSTANCE,
                MicrosecondClockImpl.INSTANCE,
                alertsSrc,
                writeSequence,
                level
        );
    }

    public LogAlertSocketWriter(
            FilesFacade ff,
            MicrosecondClock clock,
            RingQueue<LogRecordSink> alertsSrc,
            SCSequence writeSequence,
            int level
    ) {
        this.ff = ff;
        this.clock = clock;
        this.alertsSourceQueue = alertsSrc;
        this.writeSequence = writeSequence;
        this.level = level & ~(1 << Numbers.msb(LogLevel.ADVISORY)); // switch off ADVISORY
    }

    @Override
    public void bindProperties(LogFactory factory) {
        final Log log = factory.create(LogAlertSocketWriter.class.getName());
        int nInBufferSize = LogAlertSocket.IN_BUFFER_SIZE;
        if (inBufferSize != null) {
            try {
                nInBufferSize = Numbers.parseIntSize(inBufferSize);
            } catch (NumericException e) {
                throw new LogError("Invalid value for inBufferSize: " + inBufferSize);
            }
        }
        int nOutBufferSize = LogAlertSocket.OUT_BUFFER_SIZE;
        if (outBufferSize != null) {
            try {
                nOutBufferSize = Numbers.parseIntSize(outBufferSize);
            } catch (NumericException e) {
                throw new LogError("Invalid value for outBufferSize: " + outBufferSize);
            }
        }
        long nReconnectDelay = LogAlertSocket.RECONNECT_DELAY_NANO;
        if (reconnectDelay != null) {
            try {
                nReconnectDelay = Numbers.parseLong(reconnectDelay) * 1000000; // config is in milli
            } catch (NumericException e) {
                throw new LogError("Invalid value for reconnectDelay: " + reconnectDelay);
            }
        }
        if (defaultAlertHost == null) {
            defaultAlertHost = LogAlertSocket.DEFAULT_HOST;
        }
        int nDefaultPort = LogAlertSocket.DEFAULT_PORT;
        if (defaultAlertPort != null) {
            try {
                nDefaultPort = Numbers.parseInt(defaultAlertPort);
            } catch (NumericException e) {
                throw new LogError("Invalid value for defaultAlertPort: " + defaultAlertPort);
            }
        }
        socket = new LogAlertSocket(
                alertTargets,
                nInBufferSize,
                nOutBufferSize,
                nReconnectDelay,
                defaultAlertHost,
                nDefaultPort,
                log
        );
        alertSink = new HttpLogRecordSink(socket)
                .putHeader(LogAlertSocket.localHostIp)
                .setMark();
        loadLogAlertTemplate();
        socket.connect();
    }

    @Override
    public void close() {
        Misc.free(socket);
    }

    @Override
    public boolean runSerially() {
        return writeSequence.consumeAll(alertsSourceQueue, alertsProcessor);
    }

    @VisibleForTesting
    static void readFile(String location, long address, long addressSize, FilesFacade ff, CharSink sink) {
        long fdTemplate = -1;
        try (Path path = new Path()) {
            path.of(location);
            fdTemplate = ff.openRO(path.$());
            if (fdTemplate == -1) {
                throw new LogError(String.format(
                        "Cannot read %s [errno=%d]",
                        location,
                        ff.errno()
                ));
            }
            long size = ff.length(fdTemplate);
            if (size > addressSize) {
                throw new LogError("Template file is too big");
            }
            if (size < 0 || size != ff.read(fdTemplate, address, size, 0)) {
                throw new LogError(String.format(
                        "Cannot read %s [errno=%d, size=%d]",
                        location,
                        ff.errno(),
                        size
                ));
            }
            Chars.utf8Decode(address, address + size, sink);
        } finally {
            if (fdTemplate != -1) {
                ff.close(fdTemplate);
            }
        }
    }

    @VisibleForTesting
    HttpLogRecordSink getAlertSink() {
        return alertSink;
    }

    @VisibleForTesting
    String getAlertTargets() {
        return socket.getAlertTargets();
    }

    @VisibleForTesting
    void setAlertTargets(String alertTargets) {
        this.alertTargets = alertTargets;
    }

    @VisibleForTesting
    String getDefaultAlertHost() {
        return socket.getDefaultAlertHost();
    }

    @VisibleForTesting
    void setDefaultAlertHost(String defaultAlertHost) {
        this.defaultAlertHost = defaultAlertHost;
    }

    @VisibleForTesting
    int getDefaultAlertPort() {
        return socket.getDefaultAlertPort();
    }

    @VisibleForTesting
    void setDefaultAlertPort(String defaultAlertPort) {
        this.defaultAlertPort = defaultAlertPort;
    }

    @VisibleForTesting
    int getInBufferSize() {
        return socket.getInBufferSize();
    }

    @VisibleForTesting
    void setInBufferSize(String inBufferSize) {
        this.inBufferSize = inBufferSize;
    }

    @VisibleForTesting
    String getLocation() {
        return location;
    }

    @VisibleForTesting
    void setLocation(String location) {
        this.location = location;
    }

    @VisibleForTesting
    int getOutBufferSize() {
        return socket.getOutBufferSize();
    }

    @VisibleForTesting
    void setOutBufferSize(String outBufferSize) {
        this.outBufferSize = outBufferSize;
    }

    @VisibleForTesting
    long getReconnectDelay() {
        return socket.getReconnectDelay();
    }

    @VisibleForTesting
    void setReconnectDelay(String reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    private void loadLogAlertTemplate() {
        final long now = clock.getTicks();
        if (location == null || location.isEmpty()) {
            location = DEFAULT_ALERT_TPT_FILE;
        }
        location = alertTemplate.parseEnv(location, now).toString(); // location may contain dollar expressions

        // read template, resolve env vars within (except $ALERT_MESSAGE)
        boolean needsReading = true;
        try (InputStream is = LogAlertSocketWriter.class.getResourceAsStream(location)) {
            if (is != null) {
                byte[] buff = new byte[LogAlertSocket.IN_BUFFER_SIZE];
                int len = is.read(buff, 0, buff.length);
                String template = new String(buff, 0, len, Files.UTF_8);
                alertTemplate.parse(template, now, ALERT_PROPS);
                needsReading = false;
            }
        } catch (IOException e) {
            // it was not a resource ("/resource_name")
        }
        if (needsReading) {
            StringSink sink = new StringSink();
            readFile(
                    location,
                    socket.getInBufferPtr(),
                    socket.getInBufferSize(),
                    ff,
                    sink
            );
            alertTemplate.parse(sink, now, ALERT_PROPS);
        }
        if (alertTemplate.getKeyOffset(MESSAGE_ENV) < 0) {
            throw new LogError(String.format(
                    "Bad template, no %s declaration found %s",
                    MESSAGE_ENV_VALUE,
                    location));
        }
        alertTemplateNodes = alertTemplate.getTemplateNodes();
        alertTemplateNodesLen = alertTemplateNodes.size();
    }

    @VisibleForTesting
    void onLogRecord(LogRecordSink logRecord) {
        final int len = logRecord.length();
        if ((logRecord.getLevel() & level) != 0 && len > 0) {
            alertTemplate.setDateValue(clock.getTicks());
            alertSink.rewindToMark();
            for (int i = 0; i < alertTemplateNodesLen; i++) {
                TemplateNode comp = alertTemplateNodes.getQuick(i);
                if (comp.isEnv(MESSAGE_ENV)) {
                    alertSink.put(logRecord);
                } else {
                    alertSink.put(comp);
                }
            }
            socket.send(alertSink.$());
        }
    }

    static {
        if (!ALERT_PROPS.contains(ORG_ID_ENV)) {
            ALERT_PROPS.put(ORG_ID_ENV, DEFAULT_ENV_VALUE);
        }
        if (!ALERT_PROPS.contains(NAMESPACE_ENV)) {
            ALERT_PROPS.put(NAMESPACE_ENV, DEFAULT_ENV_VALUE);
        }
        if (!ALERT_PROPS.contains(CLUSTER_ENV)) {
            ALERT_PROPS.put(CLUSTER_ENV, DEFAULT_ENV_VALUE);
        }
        if (!ALERT_PROPS.contains(INSTANCE_ENV)) {
            ALERT_PROPS.put(INSTANCE_ENV, DEFAULT_ENV_VALUE);
        }
        ALERT_PROPS.put(MESSAGE_ENV, MESSAGE_ENV_VALUE);
    }
}

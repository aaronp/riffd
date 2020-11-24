java -javaagent:/opt/app/jmx/jmx_prometheus_javaagent-0.14.0.jar=8088:/opt/app/jmx/jmx.yaml \
    -Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port=9000 \
    -Dcom.sun.management.jmxremote.rmi.port=9000 \
    -Dcom.sun.management.jmxremote.local.only=false \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Djava.rmi.server.hostname=127.0.0.1 \
    -Dcom.sun.management.jmxremote.ssl=false \
    -jar /opt/app/app.jar

FROM hseeberger/scala-sbt:11.0.8_1.4.2_2.13.3 as builder

COPY . /build/
WORKDIR /build/
RUN mkdir -p /opt/app/ \
  && sbt clean "project rest" assembly \
  && mv /build/target/scala*/*assembly*jar /opt/app/app.jar

FROM openjdk:11-jre

COPY --from=builder /opt/app/ /opt/app/
ADD docker/app.sh /opt/app/app.sh

ADD docker/jmx.yaml /opt/app/jmx/jmx.yaml
RUN wget -nv -P /opt/app/jmx https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.14.0/jmx_prometheus_javaagent-0.14.0.jar

ENTRYPOINT ["bash", "/opt/app/app.sh", "$@"]
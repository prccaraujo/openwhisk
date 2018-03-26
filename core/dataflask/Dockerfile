# Use an official scala runtime as a parent image
FROM scala

ENV DEBIAN_FRONTEND noninteractive

ENV DOCKER_VERSION 1.12.0

# Uncomment to fetch latest version of docker instead: RUN wget -qO- https://get.docker.com | sh
# Install docker client
RUN wget --no-verbose https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION}.tgz && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker && \
tar --strip-components 1 -xvzf docker-${DOCKER_VERSION}.tgz -C /usr/bin docker/docker-runc && \
rm -f docker-${DOCKER_VERSION}.tgz && \
chmod +x /usr/bin/docker && \
chmod +x /usr/bin/docker-runc

# Make port 50000 available
EXPOSE 50000

# Copy app jars
COPY src/main/resources/createConf.sh ./
COPY build/distributions/dataflask.tar ./

RUN tar xf dataflask.tar && rm -f dataflask.tar

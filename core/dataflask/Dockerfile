# Use an official scala runtime as a parent image
# FROM n0ts/ubuntu-scala
FROM scala

ENV DEBIAN_FRONTEND noninteractive

# Set the working directory to /app
# WORKDIR /flasks

# Copy the current directory contents into the container at /app
# ADD . /flasks

# Install any needed packages specified in requirements.txt
# RUN pip install -r requirements.txt
# RUN apt-get install screen

# Make port 50000 available
EXPOSE 50000-59999

# Define environment variable
# ENV NAME World

# Copy app jars
COPY build/distributions/dataflasks.tar ./
COPY src/resources/local_config.txt ./
COPY src/resources/appdata1.conf ./

RUN tar xf dataflasks.tar

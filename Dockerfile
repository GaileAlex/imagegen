FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /workspace
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline
COPY src ./src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:25-jre-noble

WORKDIR /app
COPY --from=builder /workspace/target/*.jar app.jar

# /data is the volume imagegen-results: a new named volume takes the owner of the image folder.
# uid 1000 = the user comfy of cv-comfyui, so the files ComfyUI writes in the shared /comfy-io can be deleted here
# (the image has its own user "ubuntu" with uid 1000)
RUN userdel --remove ubuntu && useradd --create-home --uid 1000 spring && \
    install -d -o spring -g spring /data /comfy-io
USER spring

EXPOSE 8390
ENTRYPOINT ["java", "-jar", "app.jar"]

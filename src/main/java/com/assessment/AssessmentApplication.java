package com.assessment;

import com.assessment.service.DocumentWorkerServer;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AssessmentApplication {
    public static void main(String[] args) throws IOException {
        if ("true".equalsIgnoreCase(System.getenv("ASSESSMENT_WORKER_ONLY"))) {
            int port = Integer.parseInt(System.getenv().getOrDefault("ASSESSMENT_WORKER_PORT", "8090"));
            DocumentWorkerServer.start(System.getenv("ASSESSMENT_WORKER_TOKEN"), port);
        } else {
            SpringApplication.run(AssessmentApplication.class, args);
        }
    }
}

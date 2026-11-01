package com.playpulse.playpulse.worker.handlers;

import com.playpulse.playpulse.job.Job;
import com.playpulse.playpulse.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Placeholder: simulates sending a push notification. */
@Component
public class PushNotificationHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationHandler.class);

    @Override
    public String type() {
        return "PUSH_NOTIFICATION";
    }

    @Override
    public void handle(Job job) throws Exception {
        log.info("Sending push notification, payload={}", job.getPayload());
        Thread.sleep(300);
    }
}

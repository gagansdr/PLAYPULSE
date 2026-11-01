package com.playpulse.playpulse.worker;

import com.playpulse.playpulse.job.Job;

/** Executes one kind of job. Add a new @Component implementing this to support a new job type. */
public interface JobHandler {

    /** The Job.type this handler is responsible for. */
    String type();

    /** Do the work. Throw any exception to mark the job FAILED. */
    void handle(Job job) throws Exception;
}

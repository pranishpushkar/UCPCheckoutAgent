package dev.ucplab.service.run;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * How the run thread waits for the user's answer to {@code ask_user}. The real one is a timed {@code get}; a test
 * supplies one whose timeout it fires itself, to race the timeout against an answer (m6-plan section 3.3).
 */
@FunctionalInterface
interface AnswerWait {

    String await(CompletableFuture<String> answer, Duration timeout)
            throws TimeoutException, ExecutionException, InterruptedException;

    AnswerWait REAL = (answer, timeout) -> answer.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
}

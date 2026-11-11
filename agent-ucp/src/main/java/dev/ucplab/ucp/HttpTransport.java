package dev.ucplab.ucp;

import java.io.IOException;

/**
 * Sends one HTTP attempt. The seam between the gateway and {@code java.net.http}, so tests can inject exceptions and
 * canned replies. A transport never retries and never classifies: it returns a reply for any status, and reports
 * any failure to complete the exchange as an {@link IOException}, left unwrapped so that {@code FailureClassifier}
 * (8.1) can tell {@code HttpConnectTimeoutException} from {@code HttpTimeoutException} and the rest.
 */
public interface HttpTransport {

    /**
     * @throws IOException if no complete reply was received. An interrupt is reported as
     *     {@link java.io.InterruptedIOException} with the thread's interrupt flag set again.
     */
    HttpReply send(HttpCall call) throws IOException;
}

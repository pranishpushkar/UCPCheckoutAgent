package dev.ucplab.service.config;

import dev.ucplab.ucp.HttpTransport;
import java.util.function.Supplier;

/**
 * Where the merchant gateways and catalogs get their HTTP clients (8.5, 26.4): one new transport per client. The service
 * uses the JDK client; a test replaces the bean to answer from a scripted store, so nothing leaves the JVM.
 */
@FunctionalInterface
public interface MerchantTransports extends Supplier<HttpTransport> {
}

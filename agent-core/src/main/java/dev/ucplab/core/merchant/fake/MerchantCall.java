package dev.ucplab.core.merchant.fake;

/** The five checkout calls of {@link dev.ucplab.core.merchant.MerchantGateway} that faults can target. */
public enum MerchantCall {
    CREATE, UPDATE, COMPLETE, CANCEL, GET
}

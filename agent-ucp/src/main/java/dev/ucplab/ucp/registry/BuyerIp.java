package dev.ucplab.ucp.registry;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The operator's {@code BUYER_IP} (8.4, 19): the {@code Shopify-Buyer-IP} value for a merchant with
 * {@code requires_buyer_ip}. Read from the environment only, in the {@code local} profile only, where it is the owner's
 * own address. Personal data: {@link #toString()} never shows it (I8).
 *
 * <p>{@link #parse} accepts only a public IP literal. The format is checked first, by pattern alone, so
 * {@link InetAddress#getByName} only ever sees a literal and never looks a name up. A non-public address (loopback,
 * private, link-local, documentation and the other special ranges) is a false buyer signal and is refused.
 */
public record BuyerIp(String value) {

    public static final String INVALID = "BUYER_IP must be an IPv4 or IPv6 address literal";
    public static final String NOT_PUBLIC = "BUYER_IP must be a public address, not loopback, private, link-local or documentation";

    /** Four decimal octets of 0 to 255, no leading zeros. */
    private static final Pattern V4 = Pattern.compile("(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}");
    private static final Pattern V6_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");

    public BuyerIp {
        Objects.requireNonNull(value, "value");
    }

    /**
     * @throws IllegalArgumentException with {@link #INVALID} or {@link #NOT_PUBLIC}; the message never holds the input
     */
    public static BuyerIp parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        boolean v4 = V4.matcher(raw).matches();
        if (!v4 && !isV6Literal(raw)) {
            throw new IllegalArgumentException(INVALID);
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(raw); // a literal only (checked above): no lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(INVALID);
        }
        if (v4 != (address instanceof Inet4Address)) {
            // An IPv6 text Java turned into IPv4 (an IPv4-mapped address): an odd form, refused.
            throw new IllegalArgumentException(INVALID);
        }
        if (!isPublic(address)) {
            throw new IllegalArgumentException(NOT_PUBLIC);
        }
        return new BuyerIp(raw);
    }

    /** Eight hex groups, or fewer with one {@code ::}; no zone, brackets, port or embedded IPv4. */
    private static boolean isV6Literal(String s) {
        if (s.length() < 2 || s.length() > 39 || s.indexOf(':') < 0) {
            return false;
        }
        int gap = s.indexOf("::");
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) {
            return false;
        }
        if (gap < 0) {
            return groups(s) == 8;
        }
        String head = s.substring(0, gap);
        String tail = s.substring(gap + 2);
        int h = head.isEmpty() ? 0 : groups(head);
        int t = tail.isEmpty() ? 0 : groups(tail);
        return h >= 0 && t >= 0 && h + t <= 7;
    }

    /** The number of hex groups separated by single colons, or -1 when it is not that. */
    private static int groups(String s) {
        String[] parts = s.split(":", -1);
        for (String p : parts) {
            if (!V6_GROUP.matcher(p).matches()) {
                return -1;
            }
        }
        return parts.length;
    }

    private static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xFF;
            boolean uniqueLocal = (b0 & 0xFE) == 0xFC;                                     // fc00::/7
            boolean documentation = b0 == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D
                    && (b[3] & 0xFF) == 0xB8;                                               // 2001:db8::/32
            return !uniqueLocal && !documentation;
        }
        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;
        int o2 = b[2] & 0xFF;
        return !(o0 == 0                                       // 0.0.0.0/8
                || (o0 == 100 && (o1 & 0xC0) == 64)            // 100.64.0.0/10, shared address space
                || (o0 == 192 && o1 == 0 && o2 == 0)           // 192.0.0.0/24, IETF assignments
                || (o0 == 192 && o1 == 0 && o2 == 2)           // 192.0.2.0/24, TEST-NET-1
                || (o0 == 198 && (o1 & 0xFE) == 18)            // 198.18.0.0/15, benchmarking
                || (o0 == 198 && o1 == 51 && o2 == 100)        // 198.51.100.0/24, TEST-NET-2
                || (o0 == 203 && o1 == 0 && o2 == 113)         // 203.0.113.0/24, TEST-NET-3
                || o0 >= 240);                                 // 240.0.0.0/4 and the broadcast address
    }

    @Override
    public String toString() {
        return "BuyerIp[redacted]";
    }
}

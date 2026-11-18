package dev.ucplab.ucp.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** BUYER_IP: a public IP literal, checked with no name lookup, never printed (8.4, 19, I8; H5 C9). */
class BuyerIpTest {

    // Covers: 8.4, 19, H5 C9 (a public IPv4 or IPv6 literal is accepted as it is)
    @Test
    void publicLiterals_areAccepted() {
        assertThat(BuyerIp.parse("8.8.8.8").value()).isEqualTo("8.8.8.8");
        assertThat(BuyerIp.parse("2a00:1450:4001:80b::200e").value()).isEqualTo("2a00:1450:4001:80b::200e");
        assertThat(BuyerIp.parse("81.2.69.160").value()).isEqualTo("81.2.69.160");
    }

    // Covers: 8.4, I8, H5 C9 (anything but a literal is refused by the format check, before any parse that could look
    // a name up; the reason is fixed and never holds the input)
    @Test
    void nonLiterals_areRefusedByFormat_withAFixedReason() {
        for (String bad : List.of("", "example.com", "localhost", "8.8.8.8.evil.example", "8.8.8", "8.8.8.8.8",
                "08.8.8.8", "8.8.8.256", "0x8.8.8.8", "134744072", " 8.8.8.8", "8.8.8.8\n", "8.8.8.8:80",
                "[2a00:1450::1]", "fe80::1%en0", "::ffff:8.8.8.8", "2a00:1450:4001:80b::200e:1:2:3:4", "dead",
                "gggg::1", "1:2:3:4:5:6:7")) {
            assertThatThrownBy(() -> BuyerIp.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(BuyerIp.INVALID);
        }
    }

    // Covers: 8.4, 19, H5 C9 (a non-public address is a false buyer signal: loopback, private, link-local,
    // documentation (TEST-NET) and the other special ranges are refused with a fixed reason)
    @Test
    void nonPublicAddresses_areRefused() {
        for (String bad : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.10",
                "169.254.1.1", "192.0.2.10", "198.51.100.7", "203.0.113.5", "0.0.0.0", "100.64.0.1", "198.18.0.1",
                "224.0.0.1", "240.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1",
                "2001:db8::1", "ff02::1")) {
            assertThatThrownBy(() -> BuyerIp.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(BuyerIp.NOT_PUBLIC);
        }
        assertThat(BuyerIp.parse("172.32.0.1").value()).isEqualTo("172.32.0.1");
        assertThat(BuyerIp.parse("100.128.0.1").value()).isEqualTo("100.128.0.1");
    }

    // Covers: I8, H5 C9 (the buyer IP never prints, not even inside an Optional)
    @Test
    void toString_isMasked() {
        BuyerIp ip = BuyerIp.parse("81.2.69.160");
        assertThat(ip.toString()).isEqualTo("BuyerIp[redacted]");
        assertThat(String.valueOf(Optional.of(ip))).doesNotContain("81.2.69.160");
    }
}

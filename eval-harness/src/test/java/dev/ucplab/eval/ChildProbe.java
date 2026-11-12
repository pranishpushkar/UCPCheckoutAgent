package dev.ucplab.eval;

/**
 * A trivial main for {@link ChildJvmTest}: it loads one class from each module the agent child process will need
 * (agent-core, agent-ucp, agent-persistence with its SQLite driver, fault-proxy, merchant), then prints READY.
 */
public final class ChildProbe {

    private ChildProbe() {
    }

    public static void main(String[] args) throws ClassNotFoundException {
        for (String name : new String[] {"dev.ucplab.core.id.UuidV7IdGenerator", "dev.ucplab.ucp.UcpHttpGateway",
                "dev.ucplab.persistence.Database", "org.sqlite.JDBC", "dev.ucplab.faultproxy.FaultProxy",
                "dev.ucplab.merchant.MerchantServer"}) {
            Class.forName(name);
        }
        System.out.println("READY " + String.join(",", args));
    }
}

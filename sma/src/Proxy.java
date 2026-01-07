import proxy.ProxyUserLayer;

public class Proxy {

    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            System.out.println("Uso: java Proxy puertoEscucha looseRouting(true/false) debug(true/false)");
            return;
        }

        int listenPort      = Integer.parseInt(args[0]);
        boolean looseRouting = Boolean.parseBoolean(args[1]);
        boolean debug        = Boolean.parseBoolean(args[2]); // ahora mismo no lo usamos

        System.out.println("Proxy launching with args: " +
                listenPort + ", " + looseRouting + ", " + debug);

        ProxyUserLayer userLayer = new ProxyUserLayer(listenPort, looseRouting);
        userLayer.startListening();
    }
}

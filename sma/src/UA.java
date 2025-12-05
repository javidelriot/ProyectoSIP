import ua.UaUserLayer;

public class UA {
    public static void main(String[] args) throws Exception {

        if (args.length < 6) {
            System.err.println("Uso: java UA usuarioSIP puertoEscuchaUA IPproxy puertoProxy debug(true/false) tiempo_registro");
            return;
        }

        System.out.println("UA launching with args: " + String.join(", ", args));

        String usuarioSip   = args[0];                      // alice@SMA
        int listenPort      = Integer.parseInt(args[1]);    // 9000, 9100...
        String proxyAddress = args[2];                      // 127.0.0.1
        int proxyPort       = Integer.parseInt(args[3]);    // 5060
        boolean debug       = Boolean.parseBoolean(args[4]); // true / false
        int tiempoRegistro  = Integer.parseInt(args[5]);    // en segundos

        UaUserLayer userLayer = new UaUserLayer(
                usuarioSip,
                listenPort,
                proxyAddress,
                proxyPort,
                debug,
                tiempoRegistro
        );

        // Hilo de red
        new Thread() {
            @Override
            public void run() {
                userLayer.startListeningNetwork();
            }
        }.start();

        // 1) Registrar primero
        userLayer.startRegistration();

        // 2) Una vez registrado, ya leemos comandos de teclado
        userLayer.startListeningKeyboard();
        
        
    }
}

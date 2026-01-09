package ua;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import common.FindMyIPv4;

import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.SDPMessage;
import mensajesSIP.ServiceUnavailableMessage;

public class UaUserLayer {

    // Estados de alto nivel del UA
    private static final int IDLE              = 0;  // Sin llamada
    private static final int OUTGOING_CALL     = 1;  // Llamada saliente en curso (esperando respuesta)
    private static final int INCOMING_RINGING  = 2;  // Llamada entrante sonando
    private static final int IN_CALL           = 3;  // Llamada establecida

    private int state = IDLE;

    // INVITE entrante pendiente de aceptar/rechazar
    private InviteMessage currentIncomingInvite;

    // Timer para colgar (408) si nadie descuelga una llamada entrante
    private Timer incomingCallTimer;

    private String usuarioSip;
    private boolean debug;
    private int tiempoRegistro;
    private boolean registered = false;  // pasa a true cuando llega 200 OK al REGISTER

    // Flujo RTP permitido (ejemplo de puertos/flows)
    public static final ArrayList<Integer> RTPFLOWS =
            new ArrayList<>(Arrays.asList(96, 97, 98));

    private UaTransactionLayer transactionLayer;

    private String myAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();
    private int rtpPort;
    private int listenPort;

    // Procesos opcionales para vídeo (vitext), ahora mismo no se usan
    private Process vitextClient = null;
    private Process vitextServer = null;
    
    // Configuración por defecto para vitext (multicast)
    private static final String DEFAULT_MCAST_IP = "239.1.2.3";
    private static final int DEFAULT_VIDEO_PORT = 49172;
    
 // Info de la llamada activa (para BYE directo UA-UA)
    private String currentCallId;
    private String currentRemoteUri;      // sip:bob@SMA
    private String currentRemoteContact;  // "IP:puerto" del otro UA
    private String currentRoute;         // null si NO hay loose routing
    private String currentcSeqNumber;
    
 // ¿Estoy actualmente EN llamada?
    public boolean isInCall() {
        return state == IN_CALL;
    }


    /**
     * Constructor principal del UA.
     */
    public UaUserLayer(String usuarioSip,
                       int listenPort,
                       String proxyAddress,
                       int proxyPort,
                       boolean debug,
                       int tiempoRegistro)
            throws SocketException, UnknownHostException {

        this.usuarioSip     = usuarioSip;
        this.listenPort     = listenPort;
        this.rtpPort        = listenPort + 1;
        this.debug          = debug;
        this.tiempoRegistro = tiempoRegistro;

        this.transactionLayer = new UaTransactionLayer(
                listenPort,
                proxyAddress,
                proxyPort,
                this
        );
    
        this.transactionLayer.setDebug(this.debug);
}

    /**
     * Arranca el proceso de registro en el proxy.
     * Envía REGISTER periódicamente hasta que se recibe 200 OK.
     */
    public void startRegistration() {
        System.out.println("Iniciando registro SIP en el proxy...");

        while (!registered) {
            try {
                sendRegisterOnce();

                // Esperar un poco a ver si llega respuesta
                Thread.sleep(2000);

                if (!registered) {
                    System.out.println("No response to REGISTER, retrying...");
                }
            } catch (Exception e) {
                System.err.println("Error al enviar REGISTER: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("Registro completado correctamente. Ya puedes hacer llamadas.");
    }

    /**
     * Construye y envía un único REGISTER al proxy.
     */
    private void sendRegisterOnce() throws IOException {
        RegisterMessage register = new RegisterMessage();

        // usuarioSip tipo "alice@SMA"
        String[] partes = usuarioSip.split("@");
        String user   = partes[0];
        String domain = (partes.length > 1) ? partes[1] : "SMA";

        // Request line: REGISTER sip:DOMINIO SIP/2.0
        register.setDestination("sip:" + domain);

        // Via: IP y puerto local
        register.setVias(new ArrayList<>(
                Arrays.asList(this.myAddress + ":" + this.listenPort)));

        // Max-Forwards
        register.setMaxForwards(70);

        // To / From
        String uriUsuario = "sip:" + user + "@" + domain;

        register.setToName(user);
        register.setToUri(uriUsuario);

        register.setFromName(user);
        register.setFromUri(uriUsuario);

        // Call-ID y CSeq
        String callId = UUID.randomUUID().toString();
        register.setCallId(callId);
        int cseq = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        register.setcSeqNumber(Integer.toString(cseq));
        register.setcSeqStr("REGISTER");

        // Contact: "IP:puerto" (sin usuario)
        String contact = myAddress + ":" + listenPort;
        register.setContact(contact);

        // Expires en segundos y sin cuerpo
        register.setExpires(Integer.toString(600));
        register.setContentLength(0);

        if (debug) {
            System.out.println(">>> REGISTER a enviar:");
            System.out.println(register.toStringMessage());
        }

        transactionLayer.sendRegister(register);
    }

    /**
     * Arranca la escucha de red (UDP).
     */
    public void startListeningNetwork() {
        transactionLayer.startListeningNetwork();
    }

    /**
     * Bucle de lectura de comandos por teclado (consola).
     */
    public void startListeningKeyboard() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                prompt();
                String line = scanner.nextLine();
                if (!line.isEmpty()) {
                    command(line);
                }
            }
        } catch (Exception e) {
            System.err.println("Error leyendo comandos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Muestra el prompt en función del estado actual del UA.
     */
    private void prompt() {
        System.out.println("");
        switch (state) {
            case IDLE:
                System.out.println("INVITE xxx");
                break;
            case OUTGOING_CALL:
                System.out.println("Llamada saliente: esperando respuesta (Ringing / 200 OK / error)...");
                break;
            case INCOMING_RINGING:
                System.out.println("Llamada entrante. Comandos: ACCEPT | REJECT");
                break;
            case IN_CALL:
                // De momento no mostramos nada extra
                break;
            default:
                throw new IllegalStateException("Unexpected state: " + state);
        }
        System.out.print("> ");
    }

    /**
     * Procesa un comando de teclado según el estado.
     */
    private void command(String line) throws IOException {
        String trimmed = line.trim();
        String upper   = trimmed.toUpperCase();

        if (state == IDLE) {
            if (upper.startsWith("INVITE")) {
                commandInvite(trimmed);
            } else if ("BYE".equals(upper)) {
                System.out.println("No hay llamada activa para colgar.");
            } else {
                System.out.println("Bad command");
            }
            return;
        }

        if (state == INCOMING_RINGING) {
            if ("ACCEPT".equals(upper)) {
                acceptIncomingCall();
            } else if ("REJECT".equals(upper)) {
                rejectIncomingCall();
            } else {
                System.out.println("Comandos válidos mientras suena: ACCEPT | REJECT");
            }
            return;
        }
        
        if ("BYE".equals(upper)) {
            sendByeCommand();
            return;
        }
        if (upper.startsWith("INVITE")) {
            System.out.println("Solo se soporta una llamada simultáneamente. Cuelga antes de iniciar otra.");
            return;
        }

        // Para OUTGOING_CALL, de momento no soportamos comandos
        System.out.println("Comando no válido en el estado actual.");
    }

    /**
     * Indica si el UA está registrado (ya ha llegado 200 OK al REGISTER).
     */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * Maneja el comando INVITE xxx desde teclado.
     */
    private void commandInvite(String line) throws IOException {
        if (!isRegistered()) {
            System.out.println("No puedes hacer INVITE: el UA aún no está registrado.");
            return;
        }

        // Parar posibles clientes/servidores de vídeo anteriores
        stopVitextServer();
        stopVitextClient();

        // Formato esperado: "INVITE bob"
        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            System.out.println("Uso: INVITE nombreDestino");
            return;
        }

        String destName = parts[1];   // "bob"

        // usuarioSip p.ej. "alice@SMA"
        String[] userParts = usuarioSip.split("@");
        String myUser   = userParts[0];
        String myDomain = (userParts.length > 1) ? userParts[1] : "SMA";

        // URIs SIP
        String fromUri = "sip:" + myUser + "@" + myDomain;
        String toUri   = "sip:" + destName + "@" + myDomain;

        System.out.println("Inviting " + toUri);

        // SDP: anunciamos el vídeo en multicast (vitext)
        SDPMessage sdpMessage = new SDPMessage();
        sdpMessage.setIp(DEFAULT_MCAST_IP);
        sdpMessage.setPort(DEFAULT_VIDEO_PORT);
        sdpMessage.setOptions(RTPFLOWS);

        String callId = UUID.randomUUID().toString();
        int cseq = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        
        InviteMessage inviteMessage = new InviteMessage();
        inviteMessage.setDestination(toUri);
        inviteMessage.setVias(new ArrayList<>(
                Arrays.asList(this.myAddress + ":" + this.listenPort)));
        inviteMessage.setMaxForwards(70);

        inviteMessage.setToName(destName);
        inviteMessage.setToUri(toUri);
        inviteMessage.setFromName(myUser);
        inviteMessage.setFromUri(fromUri);

        inviteMessage.setCallId(callId);
        inviteMessage.setcSeqNumber(Integer.toString(cseq));
        inviteMessage.setcSeqStr("INVITE");

        
        // Contact: dónde nos puede contactar el otro
        inviteMessage.setContact(myAddress + ":" + listenPort);

        // Cuerpo SDP
        inviteMessage.setContentType("application/sdp");
        inviteMessage.setContentLength(
                sdpMessage.toStringMessage().getBytes().length);
        inviteMessage.setSdp(sdpMessage);

        // Mandar el INVITE mediante la capa de transacciones
        transactionLayer.call(inviteMessage);
        state = OUTGOING_CALL;
        
    }

    // =====================================================================
    //  CALLBACKS DESDE UaTransactionLayer
    // =====================================================================

    /**
     * Llega 200 OK al REGISTER.
     */
    public void onRegisterOK() {
        this.registered = true;
        System.out.println("Recibido 200 OK al REGISTER");
    }

    /**
     * Llega 404 al REGISTER: usuario no permitido.
     */
    public void onRegisterNotFound() {
        System.out.println("Recibido 404 al REGISTER. Usuario no permitido. Cerrando UA.");
        System.exit(0);
    }

    /**
     * Llega 180 Ringing a la llamada saliente.
     */
    public void onRinging() {
        System.out.println("[UA] El destino está sonando (180 Ringing)");
        // Seguimos en OUTGOING_CALL
    }

    /**
     * Llega 200 OK al INVITE (somos el llamante).
     */
    public void onInviteOKFromCallee(OKMessage ok) {
        System.out.println("[UA] Llamada establecida (200 OK). ACK enviado. /n");
        System.out.println("Para colgar la llamada ➜ BYE");
        state = IN_CALL;

        // Guardamos info de la llamada
        currentCallId         = ok.getCallId();
        currentRemoteUri      = ok.getToUri();   // el que responde
        currentRemoteContact  = ok.getContact();   // "IP:puerto" del otro UA
        currentRoute          = ok.getRecordRoute(); // null si no hay loose routing
        currentcSeqNumber     = ok.getcSeqNumber();
        /*
     // Arrancar vitextclient con la info SDP del 200 OK
        try {
            SDPMessage sdp = ok.getSdp();
            if (sdp != null) {
                runVitextClient(sdp.getIp(), sdp.getPort());
            } else {
                System.out.println("[UA] 200 OK sin SDP → no puedo arrancar vitextclient.");
            }
        } catch (IOException e) {
            System.out.println("[UA] Error arrancando vitextclient: " + e.getMessage());
        } */
    }




    /**
     * Llega un INVITE cuando somos el llamado.
     * Se guarda y se pasa a estado INCOMING_RINGING.
     * También se arranca un timer para 408 si nadie contesta en 10 s.
     */
    public void onInviteReceived(InviteMessage inv) {
        this.currentIncomingInvite = inv;
        this.state = INCOMING_RINGING;
        System.out.println("Invite received: ACCEPT | REJECT (10s time out)");
     // Guardamos si el INVITE trae Record-Route (loose routing)
        currentRoute = inv.getRecordRoute();
        
        // Cancelar timer anterior si existía
        if (incomingCallTimer != null) {
            incomingCallTimer.cancel();
        }

        // Timer: si en 10 s no se acepta/rechaza, enviamos 408
        incomingCallTimer = new java.util.Timer(true);
        incomingCallTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    if (state == INCOMING_RINGING && currentIncomingInvite != null) {
                        System.out.println("[UA] Nadie descuelga → enviando 408 Request Timeout");
                        transactionLayer.sendRequestTimeoutForInvite(currentIncomingInvite);
                        currentIncomingInvite = null;
                        state = IDLE;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, 10_000); // 10 segundos
    }

    /**
     * Llega ACK cuando somos el llamado.
     */
    public void onAckReceived() {
        System.out.println("[UA] ACK recibido → llamada establecida. /n");
        System.out.println("Para colgar la llamada ➜ BYE");
        // Si hemos aceptado una llamada, usamos el INVITE entrante
        if (currentIncomingInvite != null) {
            currentCallId    = currentIncomingInvite.getCallId();
            currentRemoteUri = currentIncomingInvite.getFromUri(); // el que nos llama
            currentcSeqNumber     = currentIncomingInvite.getcSeqNumber();

        }

        state = IN_CALL;
    }


    /**
     * Llega un error al INVITE (404 Not Found).
     */
    public void onInviteError() {
        System.out.println("[UA] Error en llamada (404 Not Found).");
        state = IDLE;
    }

    /**
     * Llega 486 Busy Here desde el callee.
     */
    public void onBusyHereFromCallee(BusyHereMessage busy) {
        System.out.println("[UA] Llamada rechazada por el destino (486 Busy Here).");
        state = IDLE;
    }

    /**
     * Llega 408 Request Timeout desde el callee.
     */
    public void onRequestTimeoutFromCallee(RequestTimeoutMessage rt) {
        System.out.println("[UA] Llamada no contestada (408 Request Timeout).");
        state = IDLE;
    }

    // =====================================================================
    //  GESTIÓN VITEXT 
    // =====================================================================

    private void runVitextClient(String ip, int port) throws IOException {
        // Cliente: reproduce (caller A)
        String cmd = "xterm -e vitext/vitextclient -p " + port + " " + ip;
        System.out.println("[VITEXT] Lanzando: " + cmd);
        vitextClient = Runtime.getRuntime().exec(cmd);
    }

    private void runVitextServer(String ip, int port) throws IOException {
        // Servidor: emite (callee B)
        String cmd = "xterm -iconic -e vitext/vitextserver -r 10 -p " + port + " vitext/1.vtx " + ip;
        System.out.println("[VITEXT] Lanzando: " + cmd);
        vitextServer = Runtime.getRuntime().exec(cmd);
    }

    private void stopVitextClient() {
        if (vitextClient != null) {
            vitextClient.destroy();
            vitextClient = null;
        }
    }

    private void stopVitextServer() {
        if (vitextServer != null) {
            vitextServer.destroy();
            vitextServer = null;
        }
    }

    // =====================================================================
    //  ACEPTAR / RECHAZAR LLAMADA ENTRANTE
    // =====================================================================

    /**
     * Acepta la llamada entrante actual.
     * Envía 200 OK con SDP y pasa a IN_CALL.
     */
    private void acceptIncomingCall() throws IOException {
    	// Guardamos info de la llamada para BYE directo
    	currentCallId        = currentIncomingInvite.getCallId();
    	currentRemoteUri     = currentIncomingInvite.getFromUri();   // quién me llama
    	currentRemoteContact = currentIncomingInvite.getContact();   // "IP:puerto" del caller
    	currentcSeqNumber    = currentIncomingInvite.getcSeqNumber();
    	
    	

    	
        if (currentIncomingInvite == null) {
            System.out.println("No hay llamada entrante que aceptar.");
            return;
        }

        // Cancelar timer de timeout
        if (incomingCallTimer != null) {
            incomingCallTimer.cancel();
            incomingCallTimer = null;
        }

     // SDP de respuesta: usamos la IP multicast y puerto ofrecidos por el caller (vitext)
        SDPMessage sdpOffer = currentIncomingInvite.getSdp();
        SDPMessage sdpMessage = new SDPMessage();
        if (sdpOffer != null) {
            sdpMessage.setIp(sdpOffer.getIp());
            sdpMessage.setPort(sdpOffer.getPort());
            sdpMessage.setOptions(sdpOffer.getOptions() != null ? sdpOffer.getOptions() : RTPFLOWS);
        } else {
            // Fallback por si el INVITE no trae SDP
            sdpMessage.setIp(DEFAULT_MCAST_IP);
            sdpMessage.setPort(DEFAULT_VIDEO_PORT);
            sdpMessage.setOptions(RTPFLOWS);
        }

        // Arrancar vitextserver con la info SDP
       // runVitextServer(sdpMessage.getIp(), sdpMessage.getPort());

        String contact = myAddress + ":" + listenPort;

        transactionLayer.sendOkForInvite(currentIncomingInvite, sdpMessage, contact);

        System.out.println("[UA] Llamada aceptada → enviado 200 OK.");
        state = IN_CALL;
    }

    /**
     * Rechaza la llamada entrante actual.
     * Envía 486 Busy Here y vuelve a IDLE.
     */
    private void rejectIncomingCall() throws IOException {
        if (currentIncomingInvite == null) {
            System.out.println("No hay llamada entrante que rechazar.");
            return;
        }

        // Cancelar timer de timeout
        if (incomingCallTimer != null) {
            incomingCallTimer.cancel();
            incomingCallTimer = null;
        }

        System.out.println("[UA] Llamada rechazada → enviando 486 Busy Here");
        transactionLayer.sendBusyForInvite(currentIncomingInvite);

        currentIncomingInvite = null;
        state = IDLE;
    }
    
    private void sendByeCommand() throws IOException {
        if (state != IN_CALL) {
            System.out.println("No hay ninguna llamada activa. No se puede enviar BYE.");
            return;
        }

        if (currentCallId == null || currentRemoteUri == null) {
            System.out.println("Error interno: información de llamada incompleta, no envío BYE.");
            return;
        }

        // Datos de este UA
        String[] userParts = usuarioSip.split("@");
        String myUser   = userParts[0];
        String myDomain = (userParts.length > 1) ? userParts[1] : "SMA";
        String fromUri  = "sip:" + myUser + "@" + myDomain;

        ByeMessage bye = new ByeMessage();
        bye.setDestination(currentRemoteUri);  // Request-URI = remoto

        // Via propia
        bye.setVias(new ArrayList<>(
                Arrays.asList(this.myAddress + ":" + this.listenPort)));

        bye.setMaxForwards(70);

        bye.setToName(null);
        bye.setToUri(currentRemoteUri);
        bye.setFromName(myUser);
        bye.setFromUri(fromUri);
       
    		
        int n = Integer.parseInt(currentcSeqNumber); // de "4" -> 4
        n = n + 1;                   // 4 -> 5
        String currentcSeqNumber1 = Integer.toString(n); 
   

        bye.setCallId(currentCallId);
        bye.setcSeqNumber(currentcSeqNumber1);
        bye.setcSeqStr("BYE");
        bye.setContentLength(0);

        // *** LOOSEROUTING ***
        if (currentRoute != null) {
            // Hay Record-Route → mandamos BYE al proxy
            bye.setRoute(currentRoute);
            System.out.println("[UA] Enviando BYE vía proxy (loose routing). cseq=" + currentcSeqNumber1);
            transactionLayer.sendBye(bye);           // a proxy
        } else {
            // Sin loose routing → BYE directo UA-UA como antes
            if (currentRemoteContact == null) {
                System.out.println("No conozco el contact remoto, no puedo mandar BYE directo.");
                return;
            }
            String[] parts = currentRemoteContact.split(":");
            String destIp   = parts[0];
            int    destPort = Integer.parseInt(parts[1]);

            System.out.println("[UA] Enviando BYE directo a " + destIp + ":" + destPort);
            transactionLayer.sendByeDirect(bye, destIp, destPort);
        }
    }




    // Llamado cuando llega 200 OK al BYE
    public void onByeOK() {
        System.out.println("[UA] BYE confirmado (200 OK) → fin de llamada.");
        state = IDLE;
        currentCallId        = null;
        currentRemoteUri     = null;
        currentRemoteContact = null;
        currentRoute         = null;
        currentIncomingInvite = null;
    }


    public String getCurrentRemoteContact() {
        return currentRemoteContact;
    }

    public void onByeReceived() {
        System.out.println("[UA] El otro extremo ha colgado (BYE recibido).");
        state = IDLE;

        stopVitextClient();
        stopVitextServer();
    }

    public String getCurrentRoute() {
        return currentRoute;
    }
    
    public void onServiceUnavailable(ServiceUnavailableMessage m) {
        System.out.println("[UA] Proxy ocupado (503 Service Unavailable).");
        state = IDLE;   // vuelves al estado IDLE
    }


}







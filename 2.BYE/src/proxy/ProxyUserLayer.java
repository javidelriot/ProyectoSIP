package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

import mensajesSIP.ACKMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.ByeMessage;

/**
 * Capa de usuario del proxy.
 * Aquí está la lógica de alto nivel:
 *  - gestionar los REGISTER y la tabla de usuarios registrados
 *  - decidir qué hacer con los INVITE
 *  - reenviar 180/200/ACK/486/408 entre caller y callee
 */
public class ProxyUserLayer {

    private ProxyTransactionLayer transactionLayer;
    private int proxyPort;

    /**
     * Información de registro de un usuario:
     *  contact  → "IP:puerto" desde donde está registrado
     *  expiresAtMs → instante en milisegundos en el que caduca el registro
     */
    private static class RegistrationInfo {
        String contact;
        long expiresAtMs;
    }

    /** Tabla de registros: "sip:usuario@dominio" -> info de registro. */
    private Map<String, RegistrationInfo> registrations = new HashMap<>();

    /**
     * Crea la capa de usuario del proxy y la capa de transacciones asociada.
     */
    public ProxyUserLayer(int listenPort) throws SocketException {
        this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
        this.proxyPort = listenPort;
    }

    // =====================================================================
    //  LÓGICA DE INVITE Y RESPUESTAS ASOCIADAS
    // =====================================================================

    /**
     * Llega un INVITE al proxy.
     *  - Comprueba que caller y callee están registrados.
     *  - Si falta alguno → 404 al caller.
     *  - Si todo OK, envía 100 Trying al caller y reenvía el INVITE al callee.
     *
     * @param inviteMessage INVITE recibido.
     * @param sourceIp      IP desde la que llega el INVITE (caller).
     * @param sourcePort    puerto UDP del caller.
     */
    public void onInviteReceived(InviteMessage inviteMessage,
                                 String sourceIp,
                                 int sourcePort) throws IOException {

        String callerUri = inviteMessage.getFromUri();
        String calleeUri = inviteMessage.getToUri();

        RegistrationInfo callerReg = getValidRegistration(callerUri);
        RegistrationInfo calleeReg = getValidRegistration(calleeUri);

        // Si el caller no está registrado, se ignora el INVITE.
        if (callerReg == null) {
            System.out.println("[Proxy] Caller NO registrado → ignorando INVITE.");
            return;
        }

        // Si el callee no está registrado, se envía 404 al caller.
        if (calleeReg == null) {
            System.out.println("[Proxy] Callee NO registrado → enviando 404.");
            transactionLayer.sendInviteNotFound(inviteMessage, callerReg.contact);
            return;
        }

        // 1) Enviar 100 Trying al llamante usando la IP/puerto de origen
        System.out.println("[Proxy] Enviando 100 Trying al llamante");
        transactionLayer.sendTrying(inviteMessage, sourceIp, sourcePort);

        // 2) Dirección real del callee a partir del REGISTER (contact = "IP:puerto")
        String[] parts = calleeReg.contact.split(":");
        String destIp = parts[0];
        int destPort = Integer.parseInt(parts[1]);

        // 3) Añadir Via del proxy (muy simple)
        inviteMessage.getVias().add(0, destIp + ":" + proxyPort);

        // 4) Reenviar el INVITE al UA llamado
        System.out.println("[Proxy] Reenviando INVITE al callee " + calleeUri
                + " en " + destIp + ":" + destPort);
        transactionLayer.forwardInvite(inviteMessage, destIp, destPort);
    }

    /**
     * Llega un 180 Ringing desde el callee al proxy.
     * Se reenvía al caller correspondiente.
     */
    public void onRingingFromCallee(RingingMessage ringing) throws IOException {

        String callerUri = ringing.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 180 Ringing al llamante");
        transactionLayer.forwardRinging(ringing, ip, port);
    }

    /**
     * Llega un 200 OK al INVITE desde el callee.
     * Se reenvía al caller asociado a esa llamada.
     */
    public void onInviteOKFromCallee(OKMessage ok) throws IOException {

        // En nuestra implementación el caller está en el From del 200 OK
        String callerUri = ok.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 200 OK al llamante");
        transactionLayer.forwardInviteOk(ok, ip, port);
    }

    /**
     * Llega un ACK desde el caller al proxy.
     * Se reenvía al callee.
     */
    public void onAckFromCaller(ACKMessage ack) throws IOException {

        String calleeUri = ack.getToUri();
        RegistrationInfo calleeReg = getValidRegistration(calleeUri);

        if (calleeReg == null) {
            return;
        }

        String[] parts = calleeReg.contact.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando ACK al callee");
        transactionLayer.forwardAck(ack, ip, port);
    }

    /**
     * Llega un 486 Busy Here desde el callee.
     * Se reenvía al caller correspondiente.
     */
    public void onBusyHereFromCallee(BusyHereMessage busy) throws IOException {

        String callerUri = busy.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            System.out.println("[Proxy] 486 Busy Here: caller no registrado, se descarta.");
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 486 Busy Here al llamante "
                + callerUri + " en " + ip + ":" + port);

        transactionLayer.forwardBusyHere(busy, ip, port);
    }

    /**
     * Llega un 408 Request Timeout desde el callee.
     * Se reenvía al caller correspondiente.
     */
    public void onRequestTimeoutFromCallee(RequestTimeoutMessage rt) throws IOException {

        String callerUri = rt.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            System.out.println("[Proxy] 408 Request Timeout: caller no registrado, se descarta.");
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 408 Request Timeout al llamante "
                + callerUri + " en " + ip + ":" + port);

        transactionLayer.forwardRequestTimeout(rt, ip, port);
    }

    // =====================================================================
    //  REGISTER Y TABLA DE REGISTROS
    // =====================================================================

    /**
     * Procesa un REGISTER recibido por el proxy.
     *  - Comprueba si el usuario está permitido (por ahora siempre true).
     *  - Actualiza la tabla de registros con contact y expires.
     *  - Envía 200 OK o 404 según corresponda.
     */
    public void onRegisterReceived(RegisterMessage registerMessage) throws IOException {

        // Usuario SIP, por ejemplo "sip:alice@SMA"
        String userUri = registerMessage.getToUri();

        // Contact y Expires
        String contact = registerMessage.getContact(); // "IP:puerto"
        int expiresSec = Integer.parseInt(registerMessage.getExpires());

        System.out.println("REGISTER recibido de " + userUri
                + " contact=" + contact
                + " expires=" + expiresSec + "s");

        // Comprobación de usuario permitido (por ahora todo OK)
        boolean valido = isUserAllowed(userUri);

        if (!valido) {
            // Usuario no permitido → 404 al REGISTER
            transactionLayer.sendRegisterResponse(registerMessage, contact, false);
            return;
        }

        // Guardar/actualizar la tabla de registros
        RegistrationInfo info = new RegistrationInfo();
        info.contact = contact;
        info.expiresAtMs = System.currentTimeMillis() + expiresSec * 1000L;

        registrations.put(userUri, info);

        // Responder 200 OK al REGISTER
        transactionLayer.sendRegisterResponse(registerMessage, contact, true);
    }
    
    public void onByeReceived(ByeMessage bye) throws IOException {

        String calleeUri = bye.getToUri();   // al que va dirigido el BYE
        RegistrationInfo calleeReg = getValidRegistration(calleeUri);

        if (calleeReg == null) {
            System.out.println("[Proxy] BYE recibido pero el destino no está registrado, se ignora.");
            return;
        }

        String[] parts = calleeReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando BYE a " + calleeUri +
                           " en " + ip + ":" + port);

        transactionLayer.forwardBye(bye, ip, port);
    }


    /**
     * Devuelve la info de registro solo si existe y no ha expirado.
     */
    private RegistrationInfo getValidRegistration(String userUri) {
        RegistrationInfo info = registrations.get(userUri);
        if (info == null) {
            return null;
        }
        if (System.currentTimeMillis() > info.expiresAtMs) {
            return null;
        }
        return info;
    }
    
    public void onByeOKFromCallee(OKMessage ok) throws IOException {

        // El que originó el BYE es quien aparece en To del 200 OK
        String callerUri = ok.getFromUri();
        RegistrationInfo callerReg = getValidRegistration(callerUri);

        if (callerReg == null) {
            System.out.println("[Proxy] 200 OK al BYE pero caller no registrado, se ignora.");
            return;
        }

        String[] parts = callerReg.contact.split(":");
        String ip   = parts[0];
        int    port = Integer.parseInt(parts[1]);

        System.out.println("[Proxy] Reenviando 200 OK al BYE a " + callerUri +
                           " en " + ip + ":" + port);

        transactionLayer.forwardInviteOk(ok, ip, port); // reutilizamos este método para OK
    }


    /**
     * Comprueba si un usuario está permitido para registrarse.
     * De momento siempre devolvemos true, pero aquí se podría
     * leer users.xml y hacer la comprobación real.
     */
    private boolean isUserAllowed(String userUri) {
        // Versión simplificada: se acepta cualquier usuario.
        return true;
    }

    // =====================================================================
    //  ARRANQUE
    // =====================================================================

    /**
     * Arranca la escucha del proxy (delegado a la capa de transacciones).
     */
    public void startListening() {
        transactionLayer.startListening();
    }
}

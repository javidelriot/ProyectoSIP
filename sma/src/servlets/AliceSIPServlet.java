package servlets;

import java.time.LocalTime;

import sipServlet.SIPServletInterface;
import sipServlet.SipServletRequestInterface;
import sipServlet.SipServletResponseInterface;
import sipServlet.ProxyInterface;

/**
 * Servlet SIP asociado al usuario sip:alice@SMA.
 *
 * Reglas (según enunciado):
 *  - Como LLAMADO (callee):
 *      * Solo acepta llamadas entre 09:00 y 17:00.
 *      * Solo acepta si el llamante es "boss" (usuario "boss").
 *      * En otro caso, responde con un 486 (Busy Here).
 *
 *  - Como LLAMANTE (caller), cuando llama a un usuario SIN servlet:
 *      * Solo puede iniciar llamadas entre 10:00 y 22:00.
 *      * Fuera de esa franja, responde 486 al INVITE.
 *
 *  Nota: según el enunciado, el contenedor garantiza que:
 *   - Si llamado tiene servlet, se ejecuta su servlet.
 *   - Si llamado no tiene y llamante sí tiene, se ejecuta el del llamante.
 *   - Si ambos tienen, solo se ejecuta el del llamado.
 */
public class AliceSIPServlet implements SIPServletInterface {

    // URI SIP de este usuario (el que tiene asociado este servlet)
    private static final String MY_URI = "sip:alice@SMA";

    // Nombre de usuario del jefe (parte "usuario" antes de la @)
    private static final String BOSS_USERNAME = "boss";

    // Horarios
    private static final LocalTime INCOMING_START = LocalTime.of(9, 0);   // 09:00
    private static final LocalTime INCOMING_END   = LocalTime.of(17, 0);  // 17:00

    private static final LocalTime OUTGOING_START = LocalTime.of(10, 0);  // 10:00
    private static final LocalTime OUTGOING_END   = LocalTime.of(22, 0);  // 11:00

    @Override
    public void doInvite(SipServletRequestInterface request) {

        String callerUri = request.getCallerURI(); // From:
        String calleeUri = request.getCalleeURI(); // To:

        boolean iAmCallee = MY_URI.equalsIgnoreCase(calleeUri);
        boolean iAmCaller = MY_URI.equalsIgnoreCase(callerUri);

        LocalTime now = LocalTime.now();

        // === CASO 1: este servlet actúa como LLAMADO (callee) ===
        if (iAmCallee) {
            handleAsCallee(request, callerUri, now);
            return;
        }

        // === CASO 2: este servlet actúa como LLAMANTE (caller) ===
        if (iAmCaller) {
            handleAsCaller(request, calleeUri, now);
            return;
        }

        // === CASO 3: por seguridad, si se ejecuta en otro contexto raro, dejamos pasar ===
        request.getProxy().proxyTo(calleeUri);
    }

    /**
     * Lógica cuando este usuario es el llamado (callee).
     *
     * Reglas:
     *  - Solo recibe llamadas de 09:00 a 17:00.
     *  - Solo acepta llamadas del usuario "boss".
     *  - En otro caso responde 486 Busy Here.
     */
    private void handleAsCallee(SipServletRequestInterface request,
                                String callerUri,
                                LocalTime now) {

        // 1) Comprobar franja horaria 09:00–17:00
        if (!isWithin(now, INCOMING_START, INCOMING_END)) {
            // Fuera de horario -> 486 Busy Here
            SipServletResponseInterface resp = request.createResponse(486);
            resp.send();
            return;
        }

        // 2) Comprobar que el llamante es el jefe ("boss")
        String callerUser = extractUserFromSipUri(callerUri);
        if (!BOSS_USERNAME.equalsIgnoreCase(callerUser)) {
            // No es el jefe -> 486 Busy Here
            SipServletResponseInterface resp = request.createResponse(486);
            resp.send();
            return;
        }

        // 3) Todo OK -> dejar que el proxy continúe la llamada hacia el callee
        ProxyInterface p = request.getProxy();
        p.proxyTo(request.getCalleeURI());
    }

    /**
     * Lógica cuando este usuario es el llamante (caller) y el llamado no tiene servlet.
     *
     * Regla:
     *  - Solo puede iniciar llamadas de 10:00 a 11:00.
     *  - Fuera de esa franja -> 486 Busy Here.
     */
    private void handleAsCaller(SipServletRequestInterface request,
                                String calleeUri,
                                LocalTime now) {

        // Comprobar franja 10:00–11:00
        if (!isWithin(now, OUTGOING_START, OUTGOING_END)) {
            SipServletResponseInterface resp = request.createResponse(486);
            resp.send();
            return;
        }

        // Dentro de horario -> permitir la llamada
        ProxyInterface p = request.getProxy();
        p.proxyTo(calleeUri);
    }

    // === Utilidades privadas ===

    /**
     * Devuelve true si time está en [start, end), es decir:
     *  start <= time < end
     */
    private boolean isWithin(LocalTime time, LocalTime start, LocalTime end) {
        return !time.isBefore(start) && time.isBefore(end);
    }

    /**
     * Extrae la parte "usuario" de una URI SIP del estilo "sip:usuario@dominio".
     */
    private String extractUserFromSipUri(String uri) {
        if (uri == null) return "";
        String lower = uri.toLowerCase().trim();
        if (!lower.startsWith("sip:")) {
            return "";
        }
        String withoutSip = lower.substring(4); // quita "sip:"
        int atIdx = withoutSip.indexOf('@');
        if (atIdx < 0) {
            return withoutSip; // raro, pero devolvemos todo
        }
        return withoutSip.substring(0, atIdx);
    }
}

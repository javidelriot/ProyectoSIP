package servlets;

import sipServlet.SIPServletInterface;
import sipServlet.SipServletRequestInterface;
import sipServlet.SipServletResponseInterface;
import sipServlet.ProxyInterface;

/**
 * Servlet SIP para el usuario sip:charlie@SMA.
 *
 * Reglas sencillas de comprobar:
 *
 *  - Como LLAMADO (callee):
 *      * Solo acepta llamadas de "alice".
 *      * Si el llamante no es alice -> 486 Busy Here.
 *
 *  - Como LLAMANTE (caller):
 *      * Solo puede llamar a "alice".
 *      * Si llama a cualquier otro usuario -> 486 Busy Here.
 *
 *  El contenedor garantiza que este servlet se ejecuta:
 *    - si Charlie es el llamado (To = sip:charlie@SMA), o
 *    - si Charlie es el llamante y el llamado no tiene servlet.
 */
public class CharlieSIPServlet implements SIPServletInterface {

    private static final String MY_URI = "sip:charlie@SMA";
    private static final String ALICE_USERNAME = "alice";

    @Override
    public void doInvite(SipServletRequestInterface request) {

        String callerUri = request.getCallerURI(); // From:
        String calleeUri = request.getCalleeURI(); // To:

        boolean iAmCallee = MY_URI.equalsIgnoreCase(calleeUri);
        boolean iAmCaller = MY_URI.equalsIgnoreCase(callerUri);

        if (iAmCallee) {
            handleAsCallee(request, callerUri);
            return;
        }

        if (iAmCaller) {
            handleAsCaller(request, calleeUri);
            return;
        }

        // Caso raro: si por alguna razón se ejecuta fuera de contexto esperado,
        // dejamos pasar la llamada para no romper nada.
        request.getProxy().proxyTo(calleeUri);
    }

    /**
     * Lógica cuando Charlie es el LLAMADO (callee).
     *
     * Solo acepta llamadas de "alice". El resto devuelven 486 Busy Here.
     */
    private void handleAsCallee(SipServletRequestInterface request,
                                String callerUri) {

        String callerUser = extractUserFromSipUri(callerUri);

        if (!ALICE_USERNAME.equalsIgnoreCase(callerUser)) {
            // Llamante distinto de Alice -> 486 Busy Here
            SipServletResponseInterface resp = request.createResponse(486);
            resp.send();
            return;
        }

        // Llamante es Alice -> se permite la llamada
        ProxyInterface p = request.getProxy();
        p.proxyTo(request.getCalleeURI());
    }

    /**
     * Lógica cuando Charlie es el LLAMANTE (caller).
     *
     * Solo puede llamar a "alice". Si intenta llamar a otro usuario -> 486.
     */
    private void handleAsCaller(SipServletRequestInterface request,
                                String calleeUri) {

        String calleeUser = extractUserFromSipUri(calleeUri);

        if (!ALICE_USERNAME.equalsIgnoreCase(calleeUser)) {
            // Destino distinto de Alice -> 486 Busy Here
            SipServletResponseInterface resp = request.createResponse(486);
            resp.send();
            return;
        }

        // Destino es Alice -> se permite la llamada
        ProxyInterface p = request.getProxy();
        p.proxyTo(calleeUri);
    }

    // ========== Utilidad para extraer el usuario de una URI SIP ==========

    /**
     * Extrae la parte "usuario" de una URI SIP del estilo "sip:usuario@dominio".
     */
    private String extractUserFromSipUri(String uri) {
        if (uri == null) return "";
        String s = uri.trim();
        if (s.toLowerCase().startsWith("sip:")) {
            s = s.substring(4); // quitar "sip:"
        }
        int atIdx = s.indexOf('@');
        if (atIdx < 0) {
            return s; // no hay @, devolvemos todo lo que haya
        }
        return s.substring(0, atIdx);
    }
}

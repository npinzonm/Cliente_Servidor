package org.vinni.cliente.gui;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.awt.*;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Cliente TCP con políticas completas:
 *
 * BACKOFF EXPONENCIAL:
 *   La espera entre reintentos se duplica con cada intento fallido.
 *   Intento 1 → DELAY_BASE s
 *   Intento 2 → DELAY_BASE * 2 s
 *   Intento 3 → DELAY_BASE * 4 s
 *   ...hasta MAX_REINTENTOS
 *
 * MENSAJES PENDIENTES:
 *   Los mensajes enviados mientras estaba desconectado se guardan
 *   localmente en ~/ClienteTCP/pendientes_NOMBRE.txt
 *   Al reconectarse se reenvían automáticamente.
 *
 * HEARTBEAT:
 *   Responde automáticamente a los pings del servidor con PONG.
 *
 * Author: Vinni 2024 | Nathalie Pinzón 2026
 */
public class PrincipalCli extends JFrame {

    // ── Configuración ─────────────────────────────────────────
    private static final String HOST             = "localhost";
    private static final int    PORT             = 12345;
    private static final int    MAX_REINTENTOS   = 5;
    private static final int    DELAY_BASE       = 2;    // segundos base para backoff
    private static final int    TIMEOUT_CONEXION = 5000; // ms

    // ── Ruta local de mensajes pendientes ─────────────────────
    private static final String DIR_CLIENTE =
            System.getProperty("user.home") + File.separator + "ClienteTCP";

    // ── Estado ────────────────────────────────────────────────
    private Socket           socket;
    private PrintWriter      out;
    private BufferedReader   in;
    private DataOutputStream dataOut;
    private String           miNombre;
    private boolean          conectado    = false;
    private boolean          reconectando = false;

    /** Cola local de mensajes pendientes (en memoria) */
    private final Queue<String[]> mensajesPendientes = new LinkedList<>();

    // ── GUI ───────────────────────────────────────────────────
    private JTextArea    mensajes;
    private JTextField   mensaje;
    private JButton      btnConectar;
    private JButton      btnEnviar;
    private JButton      btnArchivo;
    private JButton      btnDesconectar;
    private JLabel       lblEstado;
    private JLabel       lblPendientes;
    private JProgressBar barraReintentos;

    public PrincipalCli() {
        initComponents();
        new File(DIR_CLIENTE).mkdirs();
    }

    // ── INIT GUI ─────────────────────────────────────────────
    private void initComponents() {
        setTitle("Cliente TCP");
        setSize(980, 690);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // NORTE
        JPanel panelNorte = new JPanel(new BorderLayout(10, 5));
        panelNorte.setBorder(BorderFactory.createEmptyBorder(15, 20, 5, 20));

        JLabel titulo = new JLabel("CLIENTE TCP");
        titulo.setFont(new Font("Dialog", Font.BOLD, 15));
        titulo.setForeground(new Color(0, 80, 160));

        lblEstado = new JLabel("⬤ Desconectado");
        lblEstado.setFont(new Font("Dialog", Font.BOLD, 12));
        lblEstado.setForeground(Color.GRAY);

        lblPendientes = new JLabel("Pendientes: 0");
        lblPendientes.setFont(new Font("Dialog", Font.PLAIN, 12));
        lblPendientes.setForeground(new Color(150, 80, 0));

        btnConectar = new JButton("CONECTAR");
        btnConectar.setBackground(new Color(0, 150, 0));
        btnConectar.setForeground(Color.WHITE);
        btnConectar.setFont(new Font("Dialog", Font.BOLD, 12));
        btnConectar.addActionListener(e -> iniciarConexion());

        // Etiqueta con la política visible
        JLabel lblPolitica = new JLabel(
                "<html><i>" +
                        "<b>Política de reconexión:</b> " +
                        MAX_REINTENTOS + " reintentos" +
                        " | Backoff exponencial desde " + DELAY_BASE + "s" +
                        " | Timeout: " + (TIMEOUT_CONEXION / 1000) + "s por intento" +
                        "</i></html>"
        );
        lblPolitica.setFont(new Font("Dialog", Font.PLAIN, 11));
        lblPolitica.setForeground(new Color(100, 100, 100));

        barraReintentos = new JProgressBar(0, MAX_REINTENTOS);
        barraReintentos.setStringPainted(true);
        barraReintentos.setString("");
        barraReintentos.setVisible(false);

        JPanel panelInfo = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panelInfo.add(titulo);
        panelInfo.add(lblEstado);
        panelInfo.add(lblPendientes);

        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        panelBotones.add(btnConectar);

        JPanel panelTop = new JPanel(new BorderLayout());
        panelTop.add(panelInfo,    BorderLayout.WEST);
        panelTop.add(panelBotones, BorderLayout.EAST);

        panelNorte.add(panelTop,        BorderLayout.NORTH);
        panelNorte.add(lblPolitica,     BorderLayout.CENTER);
        panelNorte.add(barraReintentos, BorderLayout.SOUTH);

        // CENTRO
        mensajes = new JTextArea();
        mensajes.setEditable(false);
        mensajes.setFont(new Font("Dialog", Font.PLAIN, 13));
        mensajes.setLineWrap(true);

        JScrollPane scroll = new JScrollPane(mensajes);
        scroll.setBorder(BorderFactory.createTitledBorder("Historial"));

        // SUR
        JPanel panelSur = new JPanel(new BorderLayout(10, 0));
        panelSur.setBorder(BorderFactory.createEmptyBorder(8, 15, 12, 15));

        mensaje = new JTextField();
        mensaje.setFont(new Font("Dialog", Font.PLAIN, 13));
        mensaje.setEnabled(false);
        mensaje.addActionListener(e -> enviarMensaje());

        btnEnviar = new JButton("ENVIAR");
        btnEnviar.setEnabled(false);
        btnEnviar.addActionListener(e -> enviarMensaje());

        btnArchivo = new JButton("ENVIAR ARCHIVO");
        btnArchivo.setEnabled(false);
        btnArchivo.addActionListener(e -> enviarArchivo());

        btnDesconectar = new JButton("DESCONECTAR");
        btnDesconectar.setEnabled(false);
        btnDesconectar.setForeground(new Color(150, 0, 0));
        btnDesconectar.addActionListener(e -> desconectar());

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.add(btnArchivo);
        botones.add(btnEnviar);
        botones.add(btnDesconectar);

        panelSur.add(mensaje, BorderLayout.CENTER);
        panelSur.add(botones, BorderLayout.EAST);

        add(panelNorte, BorderLayout.NORTH);
        add(scroll,     BorderLayout.CENTER);
        add(panelSur,   BorderLayout.SOUTH);
    }

    // ── INICIAR CONEXION ──────────────────────────────────────
    private void iniciarConexion() {
        if (conectado || reconectando) return;
        btnConectar.setEnabled(false);
        new Thread(() -> conectarConBackoff(false)).start();
    }

    // ── POLÍTICA: BACKOFF EXPONENCIAL ─────────────────────────
    /**
     * Intenta conectar MAX_REINTENTOS veces.
     * La espera entre intentos se duplica con cada fallo (backoff exponencial).
     * @param esReconexion true = caída detectada, false = intento inicial
     */
    private void conectarConBackoff(boolean esReconexion) {
        reconectando = true;

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("[POLÍTICA] " + (esReconexion
                ? "Reconectando tras caída del servidor"
                : "Intentando conectar al servidor"));
        log("[POLÍTICA] Reintentos: " + MAX_REINTENTOS
                + " | Backoff desde: " + DELAY_BASE + "s"
                + " | Timeout: " + (TIMEOUT_CONEXION/1000) + "s");
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        mostrarBarra(true);
        actualizarEstado("⬤ Conectando...", new Color(200, 140, 0));

        int demoraSeg = DELAY_BASE;

        for (int intento = 1; intento <= MAX_REINTENTOS; intento++) {
            final int i = intento;
            final int demora = demoraSeg;
            SwingUtilities.invokeLater(() -> {
                barraReintentos.setValue(i);
                barraReintentos.setString("Intento " + i + "/" + MAX_REINTENTOS
                        + " — espera previa: " + demora + "s");
            });

            log("[REINTENTO " + intento + "/" + MAX_REINTENTOS + "] "
                    + "Conectando a " + HOST + ":" + PORT
                    + " (espera backoff: " + demoraSeg + "s)");

            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_CONEXION);
                s.setSoTimeout(0);

                // ── Conexión exitosa ──────────────────────────
                socket  = s;
                out     = new PrintWriter(socket.getOutputStream(), true);
                in      = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                dataOut = new DataOutputStream(socket.getOutputStream());

                if (miNombre == null) {
                    String nombre = JOptionPane.showInputDialog(this,
                            "Ingresa tu nombre:", "Nombre", JOptionPane.QUESTION_MESSAGE);
                    if (nombre == null || nombre.trim().isEmpty()) nombre = "cliente";
                    miNombre = nombre.trim();
                }

                out.println("NOMBRE:" + miNombre);
                conectado    = true;
                reconectando = false;

                mostrarBarra(false);
                actualizarEstado("⬤ Conectado: " + miNombre, new Color(0, 150, 0));
                habilitarControles(true);
                setTitle("Cliente TCP - " + miNombre);

                log("[OK] Conexión exitosa con " + HOST + ":" + PORT
                        + " (logrado en intento " + intento + ")");
                log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                escuchar();
                reenviarPendientes(); // enviar mensajes que quedaron en cola
                return;

            } catch (SocketTimeoutException ex) {
                log("[TIMEOUT] El servidor no respondió en " + (TIMEOUT_CONEXION/1000) + "s");
            } catch (ConnectException ex) {
                log("[ERROR] Servidor no disponible: " + ex.getMessage());
            } catch (IOException ex) {
                log("[ERROR] " + ex.getMessage());
            }

            // Esperar con backoff antes del siguiente intento
            if (intento < MAX_REINTENTOS) {
                log("[BACKOFF] Próximo intento en " + demoraSeg + "s...");
                try { Thread.sleep(demoraSeg * 1000L); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                demoraSeg *= 2; // duplicar la espera
            }
        }

        // ── Agotó todos los reintentos ────────────────────────
        reconectando = false;
        mostrarBarra(false);
        actualizarEstado("⬤ Sin conexión", Color.GRAY);
        habilitarControles(false);
        SwingUtilities.invokeLater(() -> btnConectar.setEnabled(true));

        int totalEspera = DELAY_BASE * ((int) Math.pow(2, MAX_REINTENTOS) - 1);
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("[AGOTADO] " + MAX_REINTENTOS + " intentos fallidos. Total espera: ~" + totalEspera + "s");
        log("[INFO] Mensajes pendientes conservados: " + mensajesPendientes.size());
        if (esReconexion)
            log("[INFO] El servidor no se reinició en el tiempo de espera.");
        else
            log("[INFO] Verifica que el servidor esté activo e intenta de nuevo.");
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this,
                        "No se pudo conectar después de " + MAX_REINTENTOS + " intentos.\n"
                                + "Mensajes pendientes conservados: " + mensajesPendientes.size(),
                        "Conexión fallida", JOptionPane.ERROR_MESSAGE)
        );
    }

    // ── HILO DE ESCUCHA ───────────────────────────────────────
    private void escuchar() {
        new Thread(() -> {
            try {
                String linea;
                while ((linea = in.readLine()) != null) {
                    // Responder heartbeat automáticamente
                    if (linea.equals("PING")) {
                        out.println("PONG");
                        log("[HEARTBEAT] Ping recibido → Pong enviado");
                        continue;
                    }
                    log(linea);
                }
                manejarCaida("El servidor cerró la conexión");
            } catch (SocketException ex) {
                if (conectado) manejarCaida("Conexión perdida: " + ex.getMessage());
            } catch (IOException ex) {
                if (conectado) manejarCaida("Error de red: " + ex.getMessage());
            }
        }).start();
    }

    // ── MANEJAR CAIDA ─────────────────────────────────────────
    private void manejarCaida(String motivo) {
        if (!conectado) return;
        conectado = false;
        cerrarSocket();
        habilitarControles(false);
        actualizarEstado("⬤ Servidor caído", new Color(180, 0, 0));

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("[CAÍDA] " + motivo);
        log("[POLÍTICA] Iniciando backoff exponencial...");

        new Thread(() -> conectarConBackoff(true)).start();
    }

    // ── ENVIAR MENSAJE ────────────────────────────────────────
    private void enviarMensaje() {
        String texto = mensaje.getText().trim();
        if (texto.isEmpty()) {
            JOptionPane.showMessageDialog(this, "El mensaje no puede estar vacío.",
                    "Vacío", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String dest = JOptionPane.showInputDialog(this,
                "Destino:\n  * o vacío = todos\n  nombre = privado",
                "Enviar", JOptionPane.QUESTION_MESSAGE);
        if (dest == null) return;
        dest = dest.trim();

        if (!conectado) {
            // POLÍTICA: guardar mensaje pendiente
            guardarPendiente(dest.isEmpty() ? "*" : dest, texto);
            JOptionPane.showMessageDialog(this,
                    "Sin conexión. Mensaje guardado como pendiente.\n"
                            + "Se enviará al reconectarse.",
                    "Mensaje encolado", JOptionPane.INFORMATION_MESSAGE);
            mensaje.setText("");
            return;
        }

        try {
            if (dest.isEmpty() || dest.equals("*")) out.println(texto);
            else out.println("/msg " + dest + " " + texto);
            mensaje.setText("");
        } catch (Exception ex) {
            log("[ERROR] No se pudo enviar: " + ex.getMessage());
            guardarPendiente(dest.isEmpty() ? "*" : dest, texto);
            manejarCaida("Error al enviar: " + ex.getMessage());
        }
    }

    // ── ENVIAR ARCHIVO ────────────────────────────────────────
    private void enviarArchivo() {
        if (!conectado) {
            JOptionPane.showMessageDialog(this, "No estás conectado.",
                    "Sin conexión", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File archivo = chooser.getSelectedFile();
        if (!archivo.exists()) {
            JOptionPane.showMessageDialog(this, "Archivo no encontrado.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String dest = JOptionPane.showInputDialog(this,
                "Destino del archivo:\n  * o vacío = todos\n  nombre = privado",
                "Destino", JOptionPane.QUESTION_MESSAGE);
        if (dest == null) return;
        dest = dest.trim();
        if (dest.isEmpty()) dest = "*";

        final String destFinal = dest;
        new Thread(() -> {
            try (FileInputStream fis = new FileInputStream(archivo)) {
                out.println("ARCHIVO:" + destFinal + ":" + archivo.getName() + ":" + archivo.length());
                byte[] buf = new byte[4096];
                int bytes;
                while ((bytes = fis.read(buf)) != -1) dataOut.write(buf, 0, bytes);
                dataOut.flush();
                log("[ARCHIVO] Enviado: " + archivo.getName() + " → " + destFinal);
            } catch (IOException ex) {
                log("[ERROR ARCHIVO] " + ex.getMessage());
                manejarCaida("Error enviando archivo");
            }
        }).start();
    }

    // ── POLÍTICA: MENSAJES PENDIENTES ─────────────────────────
    private void guardarPendiente(String destino, String texto) {
        mensajesPendientes.add(new String[]{destino, texto});
        actualizarContadorPendientes();

        // Persistir en archivo local
        if (miNombre != null) {
            File f = new File(DIR_CLIENTE, "pendientes_" + miNombre + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                pw.println(destino + "|" + texto);
            } catch (IOException ignored) {}
        }

        log("[PENDIENTE] Guardado: '" + texto + "' → " + destino
                + " (total: " + mensajesPendientes.size() + ")");
    }

    private void reenviarPendientes() {
        // Cargar pendientes del archivo si existen
        if (miNombre != null) {
            File f = new File(DIR_CLIENTE, "pendientes_" + miNombre + ".txt");
            if (f.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String linea;
                    while ((linea = br.readLine()) != null) {
                        int sep = linea.indexOf("|");
                        if (sep < 0) continue;
                        String dest = linea.substring(0, sep);
                        String msg  = linea.substring(sep + 1);
                        mensajesPendientes.add(new String[]{dest, msg});
                    }
                } catch (IOException ignored) {}
                f.delete(); // limpiar archivo tras cargar
            }
        }

        if (mensajesPendientes.isEmpty()) return;

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("[PENDIENTES] Reenviando " + mensajesPendientes.size() + " mensaje(s) pendiente(s)...");

        while (!mensajesPendientes.isEmpty()) {
            String[] item = mensajesPendientes.poll();
            String dest   = item[0];
            String texto  = item[1];
            try {
                if (dest.equals("*")) out.println(texto);
                else out.println("/msg " + dest + " " + texto);
                log("[PENDIENTE] Reenviado a " + dest + ": " + texto);
            } catch (Exception ex) {
                mensajesPendientes.add(item); // volver a encolar si falla
                log("[ERROR] No se pudo reenviar pendiente: " + ex.getMessage());
                break;
            }
        }

        actualizarContadorPendientes();
        log("[PENDIENTES] Reenvío completado.");
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ── DESCONECTAR ───────────────────────────────────────────
    private void desconectar() {
        if (!conectado) return;
        conectado = false;
        try { if (out != null) out.println("/salir"); } catch (Exception ignored) {}
        cerrarSocket();
        habilitarControles(false);
        actualizarEstado("⬤ Desconectado", Color.GRAY);
        SwingUtilities.invokeLater(() -> btnConectar.setEnabled(true));
        log("[INFO] Desconectado del servidor.");
    }

    // ── UTILIDADES ────────────────────────────────────────────
    private void cerrarSocket() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (IOException ignored) {}
    }

    private void habilitarControles(boolean activo) {
        SwingUtilities.invokeLater(() -> {
            mensaje.setEnabled(activo);
            btnEnviar.setEnabled(activo);
            btnArchivo.setEnabled(activo);
            btnDesconectar.setEnabled(activo);
            btnConectar.setEnabled(!activo && !reconectando);
        });
    }

    private void actualizarEstado(String texto, Color color) {
        SwingUtilities.invokeLater(() -> {
            lblEstado.setText(texto);
            lblEstado.setForeground(color);
        });
    }

    private void actualizarContadorPendientes() {
        SwingUtilities.invokeLater(() ->
                lblPendientes.setText("Pendientes: " + mensajesPendientes.size()));
    }

    private void mostrarBarra(boolean visible) {
        SwingUtilities.invokeLater(() -> {
            barraReintentos.setVisible(visible);
            if (!visible) { barraReintentos.setValue(0); barraReintentos.setString(""); }
        });
    }

    private void log(String texto) {
        SwingUtilities.invokeLater(() -> {
            mensajes.append(texto + "\n");
            mensajes.setCaretPosition(mensajes.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PrincipalCli().setVisible(true));
    }
}
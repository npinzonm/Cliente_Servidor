package org.vinni.cliente.gui;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.awt.*;
import java.util.LinkedList;
import java.util.Queue;

/*
 * Author: Vinni 2024 | Nathalie Pinzon 2026
 */
public class PrincipalCli extends JFrame {

    // -- Configuracion ----------------------------------------
    private static final String HOST             = "localhost";
    private static final int    PORT             = 12345;
    private static final int    PORT_ARCHIVOS    = 12346;
    private static final int    MAX_REINTENTOS   = 3;
    private static final int    DELAY_BASE       = 2;
    private static final int    TIMEOUT_CONEXION = 5000;

    // -- Validaciones -----------------------------------------
    private static final String   REGEX_NOMBRE        = "^[a-zA-Z0-9_]{2,20}$";
    private static final String[] PALABRAS_RESERVADAS = {"SERVIDOR", "TODOS", "*"};
    private static final String[] EXT_BLOQUEADAS      = {".exe", ".bat", ".sh", ".cmd", ".msi"};
    private static final long     MAX_TAMANIO_ARCHIVO = 10 * 1024 * 1024;

    // -- Ruta local de mensajes pendientes --------------------
    private static final String DIR_CLIENTE =
            System.getProperty("user.home") + File.separator + "ClienteTCP";

    private static final String ARCHIVO_COLA = "cola_mensajes.txt";

    // -- Estado -----------------------------------------------
    private Socket           socket;
    private PrintWriter      out;
    private BufferedReader   in;
    private String           miNombre;
    private boolean          conectado    = false;
    private boolean          reconectando = false;

    private final Queue<String[]> mensajesPendientes = new LinkedList<>();

    // -- GUI --------------------------------------------------
    private JTextArea    mensajes;
    private JTextField   mensaje;
    private JButton      btnConectar;
    private JButton      btnEnviar;
    private JButton      btnArchivo;
    private JButton      btnDesconectar;
    private JButton      btnListar;
    private JLabel       lblEstado;
    private JLabel       lblPendientes;
    private JProgressBar barraReintentos;

    public PrincipalCli() {
        initComponents();
        new File(DIR_CLIENTE).mkdirs();
    }

    // -- INIT GUI ---------------------------------------------
    private void initComponents() {
        setTitle("Cliente TCP");
        setSize(980, 690);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        JPanel panelNorte = new JPanel(new BorderLayout(10, 5));
        panelNorte.setBorder(BorderFactory.createEmptyBorder(15, 20, 5, 20));

        JLabel titulo = new JLabel("CLIENTE TCP");
        titulo.setFont(new Font("Dialog", Font.BOLD, 15));
        titulo.setForeground(new Color(0, 80, 160));

        lblEstado = new JLabel("Desconectado");
        lblEstado.setFont(new Font("Dialog", Font.BOLD, 12));
        lblEstado.setForeground(Color.GRAY);

        lblPendientes = new JLabel("Pendientes: 0");
        lblPendientes.setFont(new Font("Dialog", Font.PLAIN, 12));
        lblPendientes.setForeground(new Color(150, 80, 0));

        btnConectar = new JButton("CONECTAR");
        btnConectar.setBackground(new Color(0, 150, 0));
        btnConectar.setForeground(Color.WHITE);
        btnConectar.setFont(new Font("Dialog", Font.BOLD, 12));
        btnConectar.setOpaque(true);
        btnConectar.setBorderPainted(false);
        btnConectar.setFocusPainted(false);
        btnConectar.addActionListener(e -> iniciarConexion());

        JLabel lblPolitica = new JLabel(
                "<html><i>" +
                        "<b>Politica de reconexion:</b> " +
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

        mensajes = new JTextArea();
        mensajes.setEditable(false);
        mensajes.setFont(new Font("Dialog", Font.PLAIN, 13));
        mensajes.setLineWrap(true);

        JScrollPane scroll = new JScrollPane(mensajes);
        scroll.setBorder(BorderFactory.createTitledBorder("Historial"));

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

        btnListar = new JButton("VER USUARIOS");
        btnListar.setEnabled(false);
        btnListar.addActionListener(e -> listarUsuarios());

        btnDesconectar = new JButton("DESCONECTAR");
        btnDesconectar.setEnabled(false);
        btnDesconectar.setForeground(new Color(150, 0, 0));
        btnDesconectar.addActionListener(e -> desconectar());

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.add(btnListar);
        botones.add(btnArchivo);
        botones.add(btnEnviar);
        botones.add(btnDesconectar);

        panelSur.add(mensaje, BorderLayout.CENTER);
        panelSur.add(botones, BorderLayout.EAST);

        add(panelNorte, BorderLayout.NORTH);
        add(scroll,     BorderLayout.CENTER);
        add(panelSur,   BorderLayout.SOUTH);
    }

    // -- INICIAR CONEXION -------------------------------------
    // Se ejecuta en el hilo de Swing (desde el ActionListener del boton).
    // Pide el nombre aqui, antes de lanzar el hilo de backoff, para no
    // mezclar dialogos de Swing con el hilo de red.
    private void iniciarConexion() {
        if (conectado || reconectando) return;

        // Pedir nombre solo cuando no hay uno activo
        if (miNombre == null) {
            String nombre = JOptionPane.showInputDialog(
                    this,
                    "Ingresa tu nombre:",
                    "Nombre de usuario",
                    JOptionPane.QUESTION_MESSAGE);

            if (nombre == null || nombre.trim().isEmpty()) {
                return; // usuario cancelo, no hacer nada
            }

            nombre = nombre.trim();

            // VALIDACION 1: formato
            if (!nombre.matches(REGEX_NOMBRE)) {
                JOptionPane.showMessageDialog(this,
                        "Nombre invalido.\nSolo letras, numeros y _ (2-20 caracteres).",
                        "Nombre no valido", JOptionPane.WARNING_MESSAGE);
                log("[VALIDACION] Nombre rechazado: '" + nombre + "' - formato invalido.");
                return;
            }

            // VALIDACION 2: palabras reservadas
            for (String reservada : PALABRAS_RESERVADAS) {
                if (nombre.equalsIgnoreCase(reservada)) {
                    JOptionPane.showMessageDialog(this,
                            "El nombre '" + nombre + "' es una palabra reservada del sistema.",
                            "Nombre no permitido", JOptionPane.WARNING_MESSAGE);
                    log("[VALIDACION] Nombre rechazado: '" + nombre + "' - palabra reservada.");
                    return;
                }
            }

            miNombre = nombre;
        }

        // Nombre validado: deshabilitar boton y lanzar el backoff en hilo aparte
        btnConectar.setEnabled(false);
        btnConectar.setBackground(new Color(160, 160, 160));
        new Thread(() -> conectarConBackoff(false)).start();
    }

    // -- POLITICA: BACKOFF EXPONENCIAL ------------------------
    // Este metodo solo maneja red. El nombre ya esta validado y guardado
    // en miNombre antes de llegar aqui. No hay dialogos de Swing adentro.
    private void conectarConBackoff(boolean esReconexion) {
        reconectando = true;

        log("-----------------------------------------------------------------------------------------");
        log("[POLITICA] " + (esReconexion
                ? "Reconectando tras caida del servidor"
                : "Intentando conectar al servidor"));
        log("[POLITICA] Reintentos: " + MAX_REINTENTOS
                + " | Backoff desde: " + DELAY_BASE + "s"
                + " | Timeout: " + (TIMEOUT_CONEXION / 1000) + "s");
        log("-----------------------------------------------------------------------------------------");

        mostrarBarra(true);
        actualizarEstado("Conectando...", new Color(200, 140, 0));

        int demoraSeg = DELAY_BASE;

        for (int intento = 1; intento <= MAX_REINTENTOS; intento++) {
            final int i      = intento;
            final int demora = demoraSeg;
            SwingUtilities.invokeLater(() -> {
                barraReintentos.setValue(i);
                barraReintentos.setString("Intento " + i + "/" + MAX_REINTENTOS
                        + " - espera previa: " + demora + "s");
            });

            log("[REINTENTO " + intento + "/" + MAX_REINTENTOS + "] "
                    + "Conectando a " + HOST + ":" + PORT
                    + " (backoff: " + demoraSeg + "s)");

            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_CONEXION);
                s.setSoTimeout(0);

                socket = s;
                out    = new PrintWriter(socket.getOutputStream(), true);
                in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                out.println("NOMBRE:" + miNombre);
                conectado    = true;
                reconectando = false;

                mostrarBarra(false);
                actualizarEstado("Conectado: " + miNombre, new Color(0, 150, 0));
                habilitarControles(true);
                setTitle("Cliente TCP - " + miNombre);

                log("[OK] Conexion exitosa con " + HOST + ":" + PORT
                        + " (logrado en intento " + intento + ")");
                log("-------------------------------------------------------");

                reenviarPendientes();
                escuchar();
                return;

            } catch (SocketTimeoutException ex) {
                log("[TIMEOUT] El servidor no respondio en " + (TIMEOUT_CONEXION / 1000) + "s");
            } catch (ConnectException ex) {
                log("[ERROR] Servidor no disponible: " + ex.getMessage());
            } catch (IOException ex) {
                log("[ERROR] " + ex.getMessage());
            }

            if (intento < MAX_REINTENTOS) {
                log("[BACKOFF] Proximo intento en " + demoraSeg + "s...");
                try { Thread.sleep(demoraSeg * 1000L); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                demoraSeg *= 2;
            }
        }

        // -- Agoto todos los reintentos -----------------------
        finalizarReconexion();

        int totalEspera = DELAY_BASE * ((int) Math.pow(2, MAX_REINTENTOS) - 1);
        log("-----------------------------------------------------------------------------------------");
        log("[AGOTADO] " + MAX_REINTENTOS + " intentos fallidos. Total espera: ~" + totalEspera + "s");
        log("[INFO] Mensajes pendientes conservados: " + mensajesPendientes.size());
        if (esReconexion)
            log("[INFO] El servidor no se reinicio en el tiempo de espera.");
        else
            log("[INFO] Verifica que el servidor este activo e intenta de nuevo.");
        log("-----------------------------------------------------------------------------------------");

        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this,
                        "No se pudo conectar despues de " + MAX_REINTENTOS + " intentos.\n"
                                + "Mensajes pendientes conservados: " + mensajesPendientes.size(),
                        "Conexion fallida", JOptionPane.ERROR_MESSAGE)
        );
    }

    // -- HILO DE ESCUCHA --------------------------------------
    private void escuchar() {
        new Thread(() -> {
            try {
                String linea;
                while ((linea = in.readLine()) != null) {
                    if (linea.equals("PING")) {
                        out.println("PONG");
                        log("[HEARTBEAT] Ping recibido -> Pong enviado");
                        continue;
                    }
                    log(linea);
                }
                manejarCaida("El servidor cerro la conexion");
            } catch (SocketException ex) {
                if (conectado) manejarCaida("Conexion perdida: " + ex.getMessage());
            } catch (IOException ex) {
                if (conectado) manejarCaida("Error de red: " + ex.getMessage());
            }
        }).start();
    }

    // -- MANEJAR CAIDA ----------------------------------------
    private void manejarCaida(String motivo) {
        if (!conectado) return;
        conectado = false;
        cerrarSocket();
        habilitarControles(false);
        actualizarEstado("Servidor caido", new Color(180, 0, 0));

        log("-----------------------------------------------------------------------------------------");
        log("[CAIDA] " + motivo);
        log("[POLITICA] Iniciando backoff exponencial...");

        // miNombre se conserva: la reconexion automatica no debe
        // interrumpir al usuario con un dialogo de nombre.
        new Thread(() -> conectarConBackoff(true)).start();
    }

    // -- ENVIAR MENSAJE ---------------------------------------
    private void enviarMensaje() {
        String texto = mensaje.getText().trim();
        if (texto.isEmpty()) {
            JOptionPane.showMessageDialog(this, "El mensaje no puede estar vacio.",
                    "Vacio", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String dest = JOptionPane.showInputDialog(this,
                "Destino:\n  * o vacio = todos\n  nombre = privado",
                "Enviar", JOptionPane.QUESTION_MESSAGE);
        if (dest == null) return;
        dest = dest.trim();

        if (!conectado) {
            guardarPendiente(dest.isEmpty() ? "*" : dest, texto);
            JOptionPane.showMessageDialog(this,
                    "Sin conexion. Mensaje guardado como pendiente.\n"
                            + "Se enviara al reconectarse.",
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

    // -- ENVIAR ARCHIVO ---------------------------------------
    private void enviarArchivo() {
        if (!conectado) {
            JOptionPane.showMessageDialog(this, "No estas conectado.",
                    "Sin conexion", JOptionPane.WARNING_MESSAGE);
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

        String nombreLower = archivo.getName().toLowerCase();
        for (String ext : EXT_BLOQUEADAS) {
            if (nombreLower.endsWith(ext)) {
                JOptionPane.showMessageDialog(this,
                        "No se permite enviar archivos con extension " + ext + ".",
                        "Extension no permitida", JOptionPane.WARNING_MESSAGE);
                log("[VALIDACION] Archivo bloqueado por extension: " + archivo.getName());
                return;
            }
        }

        String nombreArchivo = archivo.getName();
        if (nombreArchivo.contains("..") || nombreArchivo.contains("/") || nombreArchivo.contains("\\")) {
            JOptionPane.showMessageDialog(this,
                    "Nombre de archivo no permitido.",
                    "Nombre invalido", JOptionPane.WARNING_MESSAGE);
            log("[VALIDACION] Nombre de archivo peligroso bloqueado: " + nombreArchivo);
            return;
        }

        if (archivo.length() > MAX_TAMANIO_ARCHIVO) {
            JOptionPane.showMessageDialog(this,
                    "El archivo supera el tamanio maximo de "
                            + (MAX_TAMANIO_ARCHIVO / 1024 / 1024) + " MB.",
                    "Archivo muy grande", JOptionPane.WARNING_MESSAGE);
            log("[VALIDACION] Archivo rechazado por tamanio: " + archivo.length() + " bytes");
            return;
        }

        String dest = JOptionPane.showInputDialog(this,
                "Destino del archivo:\n  * o vacio = todos\n  nombre = privado",
                "Destino", JOptionPane.QUESTION_MESSAGE);
        if (dest == null) return;
        dest = dest.trim();
        if (dest.isEmpty()) dest = "*";

        final String destFinal = dest;
        new Thread(() -> {
            try {
                out.println("ARCHIVO:" + destFinal + ":" + nombreArchivo + ":" + archivo.length());

                try (Socket socketArchivo = new Socket();
                     FileInputStream fis = new FileInputStream(archivo)) {

                    socketArchivo.connect(
                            new InetSocketAddress(HOST, PORT_ARCHIVOS), TIMEOUT_CONEXION);

                    DataOutputStream dos = new DataOutputStream(socketArchivo.getOutputStream());
                    PrintWriter metaDos  = new PrintWriter(socketArchivo.getOutputStream(), true);
                    metaDos.println(miNombre + "|" + destFinal + "|" + nombreArchivo + "|" + archivo.length());

                    byte[] buf = new byte[4096];
                    int bytes;
                    while ((bytes = fis.read(buf)) != -1) {
                        dos.write(buf, 0, bytes);
                    }
                    dos.flush();
                }

                log("[ARCHIVO] Enviado: " + nombreArchivo + " -> " + destFinal);

            } catch (IOException ex) {
                log("[ERROR ARCHIVO] " + ex.getMessage());
                manejarCaida("Error enviando archivo: " + ex.getMessage());
            }
        }).start();
    }

    // -- LISTAR USUARIOS --------------------------------------
    private void listarUsuarios() {
        if (!conectado || out == null) return;
        out.println("/users");
        log("[LISTA] Solicitando usuarios conectados...");
    }

    // -- POLITICA: MENSAJES PENDIENTES ------------------------
    private void guardarPendiente(String destino, String texto) {
        mensajesPendientes.add(new String[]{destino, texto});
        actualizarContadorPendientes();

        File f = new File(DIR_CLIENTE, ARCHIVO_COLA);
        try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
            pw.println(destino + "|" + texto);
        } catch (IOException ignored) {}

        log("[PENDIENTE] Guardado: '" + texto + "' -> " + destino
                + " (total: " + mensajesPendientes.size() + ")");
    }

    private void reenviarPendientes() {
        if (mensajesPendientes.isEmpty()) {
            File f = new File(DIR_CLIENTE, ARCHIVO_COLA);
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
                f.delete();
            }
        }

        if (mensajesPendientes.isEmpty()) return;

        log("-----------------------------------------------------------------------------------------");
        log("[PENDIENTES] Reenviando " + mensajesPendientes.size() + " mensaje(s) pendiente(s)...");

        Queue<String[]> colaEnvio = new LinkedList<>(mensajesPendientes);
        mensajesPendientes.clear();

        while (!colaEnvio.isEmpty()) {
            String[] item = colaEnvio.poll();
            String dest   = item[0];
            String texto  = item[1];
            try {
                if (dest.equals("*")) out.println(texto);
                else out.println("/msg " + dest + " " + texto);
                log("[PENDIENTE] Reenviado a " + dest + ": " + texto);
            } catch (Exception ex) {
                mensajesPendientes.add(item);
                colaEnvio.forEach(mensajesPendientes::add);
                log("[ERROR] No se pudo reenviar pendiente: " + ex.getMessage());
                break;
            }
        }

        actualizarContadorPendientes();
        log("[PENDIENTES] Reenvio completado.");
        log("-----------------------------------------------------------------------------------------");
    }

    // -- DESCONECTAR ------------------------------------------
    private void desconectar() {
        if (!conectado) return;
        conectado = false;
        try { if (out != null) out.println("/salir"); } catch (Exception ignored) {}
        cerrarSocket();
        habilitarControles(false);
        actualizarEstado("Desconectado", Color.GRAY);

        // Limpiar nombre: permite que la proxima vez que se pulse
        // CONECTAR se solicite un nuevo nombre de usuario.
        miNombre = null;

        SwingUtilities.invokeLater(() -> btnConectar.setEnabled(true));
        log("[INFO] Desconectado del servidor.");
    }

    // -- UTILIDADES -------------------------------------------
    private void cerrarSocket() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (IOException ignored) {}
    }

    private void finalizarReconexion() {
        reconectando = false;
        mostrarBarra(false);
        actualizarEstado("Desconectado", Color.GRAY);
        habilitarControles(false);
        SwingUtilities.invokeLater(() -> {
            btnConectar.setEnabled(true);
            btnConectar.setBackground(new Color(0, 150, 0));
        });
    }

    private void habilitarControles(boolean activo) {
        SwingUtilities.invokeLater(() -> {
            mensaje.setEnabled(activo);
            btnEnviar.setEnabled(activo);
            btnArchivo.setEnabled(activo);
            btnDesconectar.setEnabled(activo);
            btnListar.setEnabled(activo);
            boolean mostrarConectar = !activo && !reconectando;
            btnConectar.setEnabled(mostrarConectar);
            btnConectar.setBackground(mostrarConectar
                    ? new Color(0, 150, 0)
                    : new Color(160, 160, 160));
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
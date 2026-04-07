package org.vinni.balanceador;

import org.vinni.config.Politicas;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WatchdogMulti.java
 *
 * Monitor externo que supervisa N servidores backend independientemente.
 * Para cada puerto configurado en Politicas.WD_PUERTOS mantiene:
 *   - Contador de fallos consecutivos
 *   - Contador de reinicios realizados
 *   - Estado visual en la GUI
 *
 * Logica por servidor:
 *   - Sondea cada WD_INTERVALO_SONDEO segundos
 *   - WD_FALLOS_REINICIO fallos consecutivos → confirma caida
 *   - Verifica FLAG_APAGADO_MANUAL (apagado intencional → no reinicia)
 *   - Si caida inesperada → espera WD_DELAY_REINICIO y lanza proceso
 *     PrincipalSrv con argumento "--puerto XXXX --autostart"
 *   - Maximo WD_MAX_REINICIOS por servidor
 *
 * Author: Nathalie Pinzon 2026
 */
public class WatchdogMulti extends JFrame {

    // -- Estado por servidor --------------------------------------
    private static class EstadoServidor {
        final int    puerto;
        final AtomicInteger fallos    = new AtomicInteger(0);
        final AtomicInteger reinicios = new AtomicInteger(0);
        final AtomicBoolean vivo      = new AtomicBoolean(false);
        JLabel lblEstado;
        JLabel lblFallos;
        JLabel lblReinicios;

        EstadoServidor(int puerto) { this.puerto = puerto; }
    }

    private final List<EstadoServidor>   servidores;
    private final AtomicBoolean          monitoreando = new AtomicBoolean(false);
    private ScheduledExecutorService     scheduler;

    // -- Colores --------------------------------------------------
    private static final Color C_ACTIVO   = new Color(0, 130, 0);
    private static final Color C_CAIDO    = new Color(180, 0, 0);
    private static final Color C_WATCHDOG = new Color(80, 0, 140);
    private static final Color C_ESPERA   = new Color(160, 90, 0);
    private static final Color C_GRIS     = new Color(120, 120, 120);

    // -- GUI ------------------------------------------------------
    private JTextArea logArea;
    private JButton   btnIniciar;
    private JButton   btnDetener;
    private JPanel    panelServidores;

    public WatchdogMulti() {
        // Inicializar lista aqui para que la clase interna EstadoServidor
        // este completamente resuelta en el scope del constructor
        List<EstadoServidor> lista = new ArrayList<>();
        for (int p : Politicas.WD_PUERTOS)
            lista.add(new EstadoServidor(p));
        servidores = lista;
        initComponents();
    }

    // -- INIT GUI -------------------------------------------------
    private void initComponents() {
        setTitle("WatchdogMulti — Monitor de Backends TCP");
        setSize(700, 620);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(0, 0));

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                detenerMonitoreo();
                System.exit(0);
            }
        });

        add(construirPanelNorte(),  BorderLayout.NORTH);
        add(construirPanelLog(),    BorderLayout.CENTER);
    }

    private JPanel construirPanelNorte() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 6, 16));
        panel.setBackground(new Color(250, 248, 255));

        // Titulo y botones
        JLabel titulo = new JLabel("WATCHDOG MULTI — Monitor de Backends");
        titulo.setFont(new Font("Dialog", Font.BOLD, 14));
        titulo.setForeground(C_WATCHDOG);

        JButton btnLanzarTodos = crearBoton("LANZAR TODOS", new Color(0, 110, 170));
        btnLanzarTodos.addActionListener(e -> lanzarTodosManual());

        btnIniciar = crearBoton("INICIAR WATCHDOG", C_WATCHDOG);
        btnIniciar.addActionListener(e -> iniciarMonitoreo());

        btnDetener = crearBoton("DETENER", C_CAIDO);
        btnDetener.setEnabled(false);
        btnDetener.setBackground(C_GRIS);
        btnDetener.addActionListener(e -> detenerMonitoreo());

        JPanel filaBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filaBotones.setOpaque(false);
        filaBotones.add(btnLanzarTodos);
        filaBotones.add(btnIniciar);
        filaBotones.add(btnDetener);

        JPanel filaTitulo = new JPanel(new BorderLayout());
        filaTitulo.setOpaque(false);
        filaTitulo.add(titulo,      BorderLayout.WEST);
        filaTitulo.add(filaBotones, BorderLayout.EAST);

        // Grid de estado por servidor
        panelServidores = new JPanel(new GridLayout(
                servidores.size(), 1, 0, 4));
        panelServidores.setBorder(BorderFactory.createTitledBorder(
                "Estado por servidor backend"));
        panelServidores.setOpaque(false);

        for (EstadoServidor es : servidores) {
            JPanel fila = new JPanel(new GridLayout(1, 4, 12, 0));
            fila.setOpaque(false);
            fila.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

            JLabel lblPuerto = new JLabel(":" + es.puerto);
            lblPuerto.setFont(new Font("Monospaced", Font.BOLD, 13));
            lblPuerto.setForeground(new Color(0, 60, 130));

            es.lblEstado    = new JLabel("Desconocido");
            es.lblFallos    = new JLabel("Fallos: 0/" + Politicas.WD_FALLOS_REINICIO);
            es.lblReinicios = new JLabel("Reinicios: 0/" + Politicas.WD_MAX_REINICIOS);

            es.lblEstado.setFont(new Font("Dialog", Font.BOLD, 12));
            es.lblFallos.setFont(new Font("Dialog", Font.PLAIN, 11));
            es.lblReinicios.setFont(new Font("Dialog", Font.PLAIN, 11));

            es.lblEstado.setForeground(C_GRIS);
            es.lblFallos.setForeground(C_GRIS);
            es.lblReinicios.setForeground(C_ESPERA);

            // Boton lanzar individual
            JButton btnLanzar = crearBoton("LANZAR", new Color(0, 100, 160));
            btnLanzar.setFont(new Font("Dialog", Font.BOLD, 10));
            btnLanzar.addActionListener(e -> lanzarServidorManual(es));

            fila.add(lblPuerto);
            fila.add(es.lblEstado);
            fila.add(es.lblFallos);
            fila.add(es.lblReinicios);

            JPanel filaConBoton = new JPanel(new BorderLayout(6, 0));
            filaConBoton.setOpaque(false);
            filaConBoton.add(fila,      BorderLayout.CENTER);
            filaConBoton.add(btnLanzar, BorderLayout.EAST);

            panelServidores.add(filaConBoton);
        }

        // Config
        JLabel lblConfig = new JLabel(
                "<html><i>Sondeo: " + Politicas.WD_INTERVALO_SONDEO + "s" +
                        " | Fallos: " + Politicas.WD_FALLOS_REINICIO +
                        " | Delay: " + Politicas.WD_DELAY_REINICIO + "s" +
                        " | Max reinicios: " + Politicas.WD_MAX_REINICIOS +
                        "</i></html>"
        );
        lblConfig.setFont(new Font("Dialog", Font.PLAIN, 11));
        lblConfig.setForeground(new Color(120, 120, 120));

        panel.add(filaTitulo,       BorderLayout.NORTH);
        panel.add(panelServidores,  BorderLayout.CENTER);
        panel.add(lblConfig,        BorderLayout.SOUTH);
        return panel;
    }

    private JPanel construirPanelLog() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setBackground(new Color(248, 245, 255));
        logArea.setMargin(new Insets(6, 8, 6, 8));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(200, 185, 230)),
                "  Log del WatchdogMulti  ",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Dialog", Font.PLAIN, 11), C_WATCHDOG
        ));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 16, 12, 16));
        panel.add(scroll);
        return panel;
    }

    private JButton crearBoton(String texto, Color fondo) {
        JButton btn = new JButton(texto);
        btn.setBackground(fondo);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Dialog", Font.BOLD, 11));
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        return btn;
    }

    // -- INICIAR MONITOREO ----------------------------------------
    private void iniciarMonitoreo() {
        if (monitoreando.get()) return;
        monitoreando.set(true);

        // Resetear contadores
        for (EstadoServidor es : servidores) {
            es.fallos.set(0);
            es.reinicios.set(0);
            actualizarUIServidor(es);
        }

        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(false);
            btnIniciar.setBackground(C_GRIS);
            btnDetener.setEnabled(true);
            btnDetener.setBackground(C_CAIDO);
        });

        log("-----------------------------------------------------");
        log("[WD] Monitoreo iniciado para " + servidores.size() + " backends");
        for (EstadoServidor es : servidores)
            log("[WD]   - :" + es.puerto);
        log("-----------------------------------------------------");

        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(
                this::sondearTodos,
                0,
                Politicas.WD_INTERVALO_SONDEO,
                TimeUnit.SECONDS);
    }

    // -- SONDEO DE TODOS LOS SERVIDORES ---------------------------
    private void sondearTodos() {
        if (!monitoreando.get()) return;
        for (EstadoServidor es : servidores)
            sondearServidor(es);
    }

    private void sondearServidor(EstadoServidor es) {
        boolean vivo = intentarConexion(es.puerto);

        if (vivo) {
            es.fallos.set(0);
            es.vivo.set(true);
            log("[SONDEO] :" + es.puerto + " → OK");
            actualizarEstadoServidor(es, "ACTIVO", C_ACTIVO);
            actualizarFallosUI(es, 0);
        } else {
            es.vivo.set(false);
            int f = es.fallos.incrementAndGet();
            log("[SONDEO] :" + es.puerto + " → SIN RESPUESTA  fallo " +
                    f + "/" + Politicas.WD_FALLOS_REINICIO);
            actualizarEstadoServidor(es, "CAIDO (" + f + "/"
                    + Politicas.WD_FALLOS_REINICIO + ")", C_CAIDO);
            actualizarFallosUI(es, f);

            if (f >= Politicas.WD_FALLOS_REINICIO) {
                es.fallos.set(0);
                actualizarFallosUI(es, 0);
                manejarCaidaServidor(es);
            }
        }
    }

    // -- MANEJAR CAIDA DE UN SERVIDOR -----------------------------
    private void manejarCaidaServidor(EstadoServidor es) {
        // Verificar flag de apagado manual
        File flag = new File(Politicas.FLAG_APAGADO_MANUAL);
        if (flag.exists()) {
            flag.delete();
            log("-----------------------------------------------------");
            log("[WD] :" + es.puerto + " — Flag apagado manual detectado.");
            log("[WD] Apagado intencional — NO se reinicia.");
            log("-----------------------------------------------------");
            actualizarEstadoServidor(es, "Apagado manual", C_ESPERA);
            return;
        }

        if (es.reinicios.get() >= Politicas.WD_MAX_REINICIOS) {
            log("-----------------------------------------------------");
            log("[WD] :" + es.puerto + " — Limite de reinicios alcanzado ("
                    + Politicas.WD_MAX_REINICIOS + "). Accion manual requerida.");
            log("-----------------------------------------------------");
            actualizarEstadoServidor(es, "Limite alcanzado", C_CAIDO);
            return;
        }

        es.reinicios.incrementAndGet();
        actualizarReinciosUI(es);

        log("-----------------------------------------------------");
        log("[WD] :" + es.puerto + " — Caida confirmada. Reinicio "
                + es.reinicios.get() + "/" + Politicas.WD_MAX_REINICIOS);
        log("[WD] Relanzando en " + Politicas.WD_DELAY_REINICIO + "s...");
        actualizarEstadoServidor(es, "Reiniciando...", C_ESPERA);

        new Thread(() -> {
            for (int s = Politicas.WD_DELAY_REINICIO; s >= 1; s--) {
                log("[WD] :" + es.puerto + " relanzando en " + s + "s...");
                try { Thread.sleep(1000); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            lanzarProceso(es);
        }).start();
    }

    // -- LANZAR PROCESO -------------------------------------------
    private void lanzarProceso(EstadoServidor es) {
        try {
            String java      = ProcessHandle.current().info().command().orElse("java");
            String classpath = System.getProperty("java.class.path");

            if (classpath == null || classpath.isBlank()) {
                log("[ERROR] :" + es.puerto
                        + " — classpath vacio, usa boton LANZAR manual.");
                return;
            }

            // --puerto indica al servidor que puerto abrir
            // --autostart indica que inicie el ServerSocket automaticamente
            ProcessBuilder pb = new ProcessBuilder(
                    java, "-cp", classpath,
                    Politicas.CLASE_SERVIDOR,
                    "--puerto", String.valueOf(es.puerto),
                    "--autostart");
            pb.redirectErrorStream(true);
            pb.start();

            log("[WD] :" + es.puerto + " proceso lanzado.");
            Thread.sleep(3000);

            if (intentarConexion(es.puerto)) {
                log("[WD] :" + es.puerto + " activo tras reinicio — OK");
                actualizarEstadoServidor(es, "ACTIVO", C_ACTIVO);
                es.vivo.set(true);
            } else {
                log("[WD] :" + es.puerto + " aun no responde, sondeo continuara.");
            }

        } catch (IOException ex) {
            log("[ERROR] :" + es.puerto + " no se pudo lanzar: " + ex.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // -- LANZAR TODOS MANUAL --------------------------------------
    private void lanzarTodosManual() {
        log("-----------------------------------------------------");
        log("[WD] Lanzando todos los backends manualmente...");
        // Borrar flag si existe
        File flag = new File(Politicas.FLAG_APAGADO_MANUAL);
        if (flag.exists()) { flag.delete(); log("[WD] Flag manual eliminado."); }

        for (EstadoServidor es : servidores) {
            es.reinicios.set(0);
            actualizarReinciosUI(es);
            lanzarProceso(es);
        }
    }

    private void lanzarServidorManual(EstadoServidor es) {
        log("[WD] Lanzando manualmente :" + es.puerto);
        es.reinicios.set(0);
        actualizarReinciosUI(es);
        lanzarProceso(es);
    }

    // -- DETENER MONITOREO ----------------------------------------
    private void detenerMonitoreo() {
        monitoreando.set(false);
        if (scheduler != null && !scheduler.isShutdown())
            scheduler.shutdownNow();

        SwingUtilities.invokeLater(() -> {
            btnIniciar.setEnabled(true);
            btnIniciar.setBackground(C_WATCHDOG);
            btnDetener.setEnabled(false);
            btnDetener.setBackground(C_GRIS);
        });
        log("[WD] Monitoreo detenido.");
    }

    // -- SONDEO ---------------------------------------------------
    private boolean intentarConexion(int puerto) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("localhost", puerto),
                    Politicas.WD_TIMEOUT_SONDEO);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -- ACTUALIZAR UI --------------------------------------------
    private void actualizarEstadoServidor(EstadoServidor es, String txt, Color c) {
        SwingUtilities.invokeLater(() -> {
            es.lblEstado.setText(txt);
            es.lblEstado.setForeground(c);
        });
    }

    private void actualizarFallosUI(EstadoServidor es, int f) {
        SwingUtilities.invokeLater(() -> {
            es.lblFallos.setText("Fallos: " + f + "/" + Politicas.WD_FALLOS_REINICIO);
            es.lblFallos.setForeground(f > 0 ? C_CAIDO : C_GRIS);
        });
    }

    private void actualizarReinciosUI(EstadoServidor es) {
        SwingUtilities.invokeLater(() ->
                es.lblReinicios.setText(
                        "Reinicios: " + es.reinicios.get()
                                + "/" + Politicas.WD_MAX_REINICIOS));
    }

    private void actualizarUIServidor(EstadoServidor es) {
        actualizarEstadoServidor(es, "Desconocido", C_GRIS);
        actualizarFallosUI(es, 0);
        actualizarReinciosUI(es);
    }

    // -- LOG ------------------------------------------------------
    private void log(String texto) {
        String hora = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + hora + "]  " + texto + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new WatchdogMulti().setVisible(true));
    }
}
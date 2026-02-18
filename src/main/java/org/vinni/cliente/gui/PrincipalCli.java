package org.vinni.cliente.gui;

import javax.swing.*;
import java.io.*;
import java.net.Socket;
import java.awt.*;

public class PrincipalCli extends JFrame {

    private final int PORT = 12345;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    private JTextArea mensajes;
    private JTextField mensaje;
    private JButton conectar, enviar;

    public PrincipalCli() {
        initComponents();
    }

    private void initComponents() {

        setTitle("Cliente TCP");
        setSize(600, 450);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null); // Centra la ventana

        setLayout(new BorderLayout(10, 10));

        JPanel panelNorte = new JPanel(new GridLayout(1, 2, 10, 0));
        panelNorte.setBorder(BorderFactory.createEmptyBorder(20, 25, 30, 25));
        panelNorte.setBackground(Color.WHITE);

        // 1. Instrucciones
        JLabel etiquetasInstrucciones = new JLabel("<html><b>Instrucciones:</b><br>Da clic en conectar al servidor y luego ingresa tu nombre. </html>");
        etiquetasInstrucciones.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        panelNorte.add(etiquetasInstrucciones);

        // 2. Botón conectar
        JPanel contenedorBoton = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        contenedorBoton.setOpaque(false); // Para que tome el color del panel padre

        conectar = new JButton("Conectar a Servidor");
        //estilos
        styleButtons(conectar);

        contenedorBoton.add(conectar);
        panelNorte.add(contenedorBoton);
        add(panelNorte, BorderLayout.NORTH);


        // --- PANEL CENTRAL (Chat) ---
        mensajes = new JTextArea();
        mensajes.setEditable(false);
        mensajes.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        mensajes.setLineWrap(true);
        mensajes.setWrapStyleWord(true);
        mensajes.setBackground(new Color(245, 245, 245));

        JScrollPane scroll = new JScrollPane(mensajes);
        scroll.setBorder(BorderFactory.createTitledBorder("Historial de Mensajes"));
        JPanel panelCentro = new JPanel(new BorderLayout());
        panelCentro.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 15));
        panelCentro.add(scroll, BorderLayout.CENTER);

        // --- PANEL INFERIOR (Envío) ---
        JPanel panelSur = new JPanel(new BorderLayout(10, 0));
        panelSur.setBorder(BorderFactory.createEmptyBorder(10, 15, 15, 15));

        mensaje = new JTextField();
        mensaje.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        enviar = new JButton("ENVIAR");
        enviar.setBackground(new Color(60, 179, 113)); // Verde mar suave
        enviar.setForeground(Color.WHITE);
        enviar.setFocusPainted(false);

        panelSur.add(mensaje, BorderLayout.CENTER);
        panelSur.add(enviar, BorderLayout.EAST);

        // Agregar paneles al frame principal
        add(panelNorte, BorderLayout.NORTH);
        add(panelCentro, BorderLayout.CENTER);
        add(panelSur, BorderLayout.SOUTH);

        // Eventos
        conectar.addActionListener(e -> conectar());
        enviar.addActionListener(e -> enviarMensaje());
        // Enviar mensaje al presionar "Enter" en el teclado
        mensaje.addActionListener(e -> enviarMensaje());
    }

    private void conectar() {
        try {
            socket = new Socket("localhost", PORT);

            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String nombre = JOptionPane.showInputDialog(this, "Nombre del cliente:");
            out.println("NOMBRE:" + nombre);
            setTitle("Cliente - " + nombre);

            new Thread(() -> {
                try {
                    String msg;
                    while ((msg = in.readLine()) != null) {
                        mensajes.append(msg + "\n");
                    }
                } catch (IOException e) {
                    mensajes.append("Desconectado del servidor\n");
                }
            }).start();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo conectar");
        }
    }

    private void enviarMensaje() {
        if (out == null) {
            JOptionPane.showMessageDialog(this, "No estás conectado al servidor");
            return;
        }

        String texto = mensaje.getText().trim();
        if (texto.isEmpty()) {
            return;
        }

        // Preguntar destinatario
        String destinatario = JOptionPane.showInputDialog(
                this,
                "¿A quién deseas enviar el mensaje?\n" +
                        "• Deja vacío o escribe * para todos\n" +
                        "• Escribe el nombre del cliente para mensaje privado",
                "Destino del mensaje",
                JOptionPane.QUESTION_MESSAGE
        );

        // Si cancelan el diálogo
        if (destinatario == null) {
            return;
        }

        destinatario = destinatario.trim();

        // Mensaje global
        if (destinatario.isEmpty() || destinatario.equals("*")) {
            out.println(texto);
        }
        // Mensaje privado
        else {
            out.println("/msg " + destinatario + " " + texto);
        }

        mensaje.setText("");
    }



    private void styleButtons(JButton boton){
        boton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        conectar.setBackground(Color.BLACK);
        boton.setForeground(Color.WHITE);

        boton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.BLACK, 1), // Borde exterior sutil
                BorderFactory.createEmptyBorder(10, 20, 10, 20) // El PADDING real
        ));

        boton.setFocusPainted(false);
        boton.setContentAreaFilled(true);
        boton.setOpaque(true);
        boton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        boton.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                boton.setBackground(new Color(50, 50, 50)); // Gris oscuro al pasar el mouse
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                boton.setBackground(Color.BLACK); // Vuelve a negro
            }
        });
    }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PrincipalCli().setVisible(true));
    }
}

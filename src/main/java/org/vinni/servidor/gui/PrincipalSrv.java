package org.vinni.servidor.gui;


import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Vinni
 */
public class PrincipalSrv extends javax.swing.JFrame {
    private final int PORT = 12345;
    private ServerSocket serverSocket;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final AtomicInteger clientCounter = new AtomicInteger(1);

    /**
     * Creates new form Principal1
     */
    public PrincipalSrv() {
        initComponents();
    }
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">
    private void initComponents() {
        this.setTitle("Servidor ...");

        bIniciar = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        mensajesTxt = new JTextArea();
        jScrollPane1 = new javax.swing.JScrollPane();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        getContentPane().setLayout(null);

        bIniciar.setFont(new java.awt.Font("Segoe UI", 0, 18)); // NOI18N
        bIniciar.setText("INICIAR SERVIDOR");
        bIniciar.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                bIniciarActionPerformed(evt);
            }
        });
        getContentPane().add(bIniciar);
        bIniciar.setBounds(100, 90, 250, 40);

        jLabel1.setFont(new java.awt.Font("Tahoma", 1, 14)); // NOI18N
        jLabel1.setForeground(new java.awt.Color(204, 0, 0));
        jLabel1.setText("SERVIDOR TCP : HOEL");
        getContentPane().add(jLabel1);
        jLabel1.setBounds(150, 10, 160, 17);

        mensajesTxt.setColumns(25);
        mensajesTxt.setRows(5);

        jScrollPane1.setViewportView(mensajesTxt);

        getContentPane().add(jScrollPane1);
        jScrollPane1.setBounds(20, 160, 410, 70);

        setSize(new java.awt.Dimension(491, 290));
        setLocationRelativeTo(null);
    }// </editor-fold>

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new PrincipalSrv().setVisible(true);
            }
        });

    }
    private void bIniciarActionPerformed(java.awt.event.ActionEvent evt) {
        iniciarServidor();
    }

    private void iniciarServidor() {
        JOptionPane.showMessageDialog(this, "Iniciando servidor");
        new Thread(new Runnable() {
            public void run() {
                try {
                    InetAddress addr = InetAddress.getLocalHost();
                    serverSocket = new ServerSocket(PORT);
                    mensajesTxt.append("Servidor TCP en ejecución: "+ addr + " ,Puerto " + serverSocket.getLocalPort()+ "\n");
                    while (true) {
                        Socket clientSocket = serverSocket.accept();
                        ClientHandler handler = new ClientHandler(clientSocket);
                        handler.start();
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                    mensajesTxt.append("Error en el servidor: " + ex.getMessage() + "\n");
                }
            }
        }).start();
    }

    private void appendServerMessage(String message) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                mensajesTxt.append(message + "\n");
            }
        });
    }

    private void broadcast(String fromClient, String message) {
        String formatted = "[" + fromClient + "]: " + message;
        for (ClientHandler handler : clients.values()) {
            handler.send(formatted);
        }
        appendServerMessage(formatted);
    }

    private void sendToClient(String fromClient, String toClient, String message) {
        ClientHandler target = clients.get(toClient);
        if (target == null) {
            ClientHandler sender = clients.get(fromClient);
            if (sender != null) {
                sender.send("Servidor: Cliente no encontrado: " + toClient);
            }
            appendServerMessage("Intento fallido de " + fromClient + " hacia " + toClient + ": " + message);
            return;
        }
        String formatted = "[DM de " + fromClient + "]: " + message;
        target.send(formatted);
        appendServerMessage(formatted);
    }

    private String registerClientName(String requestedName) {
        String baseName = requestedName == null ? "" : requestedName.trim();
        if (baseName.isEmpty()) {
            baseName = "cliente-" + clientCounter.getAndIncrement();
        }
        String candidate = baseName;
        int suffix = 1;
        while (clients.containsKey(candidate)) {
            candidate = baseName + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private class ClientHandler extends Thread {
        private final Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String clientName;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String firstLine = in.readLine();
                if (firstLine != null && firstLine.startsWith("NOMBRE:")) {
                    clientName = registerClientName(firstLine.substring("NOMBRE:".length()));
                } else {
                    clientName = registerClientName("");
                }
                clients.put(clientName, this);
                send("Servidor: Conectado como " + clientName);
                appendServerMessage("Cliente conectado: " + clientName);

                String linea;
                while ((linea = in.readLine()) != null) {
                    if (linea.startsWith("/msg ")) {
                        String payload = linea.substring(5).trim();
                        int spaceIdx = payload.indexOf(' ');
                        if (spaceIdx <= 0) {
                            send("Servidor: Uso correcto: /msg <cliente> <mensaje>");
                            continue;
                        }
                        String target = payload.substring(0, spaceIdx).trim();
                        String body = payload.substring(spaceIdx + 1).trim();
                        sendToClient(clientName, target, body);
                    } else {
                        broadcast(clientName, linea);
                    }
                }
            } catch (IOException ex) {
                appendServerMessage("Error con cliente: " + ex.getMessage());
            } finally {
                if (clientName != null) {
                    clients.remove(clientName);
                    appendServerMessage("Cliente desconectado: " + clientName);
                }
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        void send(String message) {
            if (out != null) {
                out.println(message);
            }
        }
    }

    // Variables declaration - do not modify
    private javax.swing.JButton bIniciar;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JTextArea mensajesTxt;
    private javax.swing.JScrollPane jScrollPane1;
}

package org.vinni.servidor.gui;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PrincipalSrv extends JFrame {

    private final int PORT = 12345;
    private ServerSocket serverSocket;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(1);

    private JButton bIniciar;
    private JTextArea mensajesTxt;

    public PrincipalSrv() {
        initComponents();
    }

    private void initComponents() {
        setTitle("Servidor TCP");
        setSize(500, 300);
        setLayout(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        bIniciar = new JButton("INICIAR SERVIDOR");
        bIniciar.setBounds(120, 40, 250, 40);
        add(bIniciar);

        mensajesTxt = new JTextArea();
        mensajesTxt.setEditable(false);
        JScrollPane scroll = new JScrollPane(mensajesTxt);
        scroll.setBounds(20, 100, 450, 140);
        add(scroll);

        bIniciar.addActionListener(e -> iniciarServidor());
        setLocationRelativeTo(null);
    }

    private void iniciarServidor() {
        mensajesTxt.append("Iniciando servidor...\n");
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                mensajesTxt.append("Servidor activo en puerto " + PORT + "\n");

                while (true) {
                    Socket socket = serverSocket.accept();
                    new ClientHandler(socket).start();
                }
            } catch (IOException e) {
                mensajesTxt.append("Error servidor: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private void broadcast(String from, String msg) {
        String formatted = "[" + from + "]: " + msg;
        clients.values().forEach(c -> c.send(formatted));
        mensajesTxt.append(formatted + "\n");
    }

    private void sendPrivate(String from, String to, String msg) {
        ClientHandler target = clients.get(to);
        if (target != null) {
            target.send("[Privado de " + from + "]: " + msg);
        } else {
            clients.get(from).send("Servidor: Cliente no encontrado");
        }
    }

    private String generateName(String base) {
        if (base == null || base.isBlank()) {
            return "cliente-" + counter.getAndIncrement();
        }
        String name = base;
        int i = 1;
        while (clients.containsKey(name)) {
            name = base + "-" + i++;
        }
        return name;
    }

    private class ClientHandler extends Thread {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String name;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String line = in.readLine();
                name = generateName(line.replace("NOMBRE:", ""));
                clients.put(name, this);

                send("Servidor: Bienvenido " + name);
                broadcast("Servidor", name + " se ha conectado");

                while ((line = in.readLine()) != null) {
                    if (line.equals("/users")) {
                        send("Usuarios conectados: " + clients.keySet());
                    } else if (line.startsWith("/msg ")) {
                        String[] parts = line.split(" ", 3);
                        if (parts.length == 3) {
                            sendPrivate(name, parts[1], parts[2]);
                        }
                    } else {
                        broadcast(name, line);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                clients.remove(name);
                broadcast("Servidor", name + " se ha desconectado");
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        void send(String msg) {
            out.println(msg);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PrincipalSrv().setVisible(true));
    }
}

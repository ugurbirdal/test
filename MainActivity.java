package com.example.watertankmonitor;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private View waterView;
    private TextView txtLevel, txtStatus;
    private Button btnConnect;

    private static final String PLC_IP = "192.168.1.5";
    private static final int PLC_PORT = 502; // Standart Modbus TCP Portu

    private ScheduledExecutorService scheduler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        waterView = findViewById(R.id.waterView);
        txtLevel = findViewById(R.id.txtLevel);
        txtStatus = findViewById(R.id.txtStatus);
        btnConnect = findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(v -> readModbusData());

        // Periyodik olarak her 2 saniyede bir veriyi otomatik oku
        startPeriodicReading();
    }

    private void startPeriodicReading() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::readModbusData, 0, 2, TimeUnit.SECONDS);
    }

    private void readModbusData() {
        try {
            // Sockets üzerinden Modbus TCP Bağlantısı (Function Code 03: Read Holding Registers)
            // 40001 adresi, Modbus protokolünde Offset 0 (0x0000) anlamına gelir.
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(PLC_IP, PLC_PORT), 2000); // 2 saniye timeout

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Modbus TCP Request Frame (MBAP Header + PDU)
            // Transaction ID: 0x0001, Protocol ID: 0x0000, Length: 0x0006, Unit ID: 0x01
            // Function Code: 0x03 (Read Holding Registers), Start Address: 0x0000 (40001), Quantity: 0x0001
            byte[] request = {
                    0x00, 0x01, // Transaction Identifier
                    0x00, 0x00, // Protocol Identifier
                    0x00, 0x06, // Length
                    0x01,       // Unit Identifier (Slave ID)
                    0x03,       // Function Code (Read Holding Registers)
                    0x00, 0x00, // Starting Address High & Low (40001 -> 0)
                    0x00, 0x01  // Quantity of Registers to read (1 adet)
            };

            out.write(request);
            out.flush();

            // Cevap paketini oku
            byte[] response = new byte[256];
            int bytesRead = in.read(response);
            socket.close();

            if (bytesRead > 9) {
                // Modbus TCP Yanıtında Data Alanı 9. indexten başlar
                // High byte ve Low byte birleştirilerek register değeri elde edilir
                int highByte = response[9] & 0xFF;
                int lowByte = response[10] & 0xFF;
                int registerValue = (highByte << 8) | lowByte;

                // Değerin 0 - 100 arasında bir yüzde olduğunu varsayalım (Gerekirse ölçeklendirebilirsiniz)
                final int levelPercent = Math.min(Math.max(registerValue, 0), 100);

                // UI Güncellemesini Main Thread üzerinde yap
                new Handler(Looper.getMainLooper()).post(() -> updateTankUI(levelPercent, "Bağlantı Başarılı"));
            } else {
                showErrorStatus("Geçersiz Yanıt");
            }

        } catch (Exception e) {
            e.printStackTrace();
            showErrorStatus("Bağlantı Hatası: " + e.getMessage());
        }
    }

    private void updateTankUI(int percent, String statusText) {
        txtStatus.setText(statusText);
        txtStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        txtLevel.setText("%" + percent);

        // Su tankı görünümünün yüksekliğini yüzdeye göre ayarla (Maksimum yükseklik 280dp'dir, pixel hesabına çevrilir)
        // 280dp'yi piksele çeviriyoruz
        float density = getResources().getDisplayMetrics().density;
        int maxHeightPx = (int) (280 * density);

        android.view.ViewGroup.LayoutParams params = waterView.getLayoutParams();
        params.height = (maxHeightPx * percent) / 100;
        waterView.setLayoutParams(params);
    }

    private void showErrorStatus(String errorMsg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            txtStatus.setText(errorMsg);
            txtStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}

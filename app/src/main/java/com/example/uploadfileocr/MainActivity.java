package com.example.uploadfileocr;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.speech.tts.TextToSpeech;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_FILE_REQUEST_CODE = 1;
    private TextRecognizer textRecognizer;
    private TextView textView;
    private TextToSpeech textToSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Kiểm tra và yêu cầu quyền ghi bộ nhớ ngoài
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }

        textRecognizer = new TextRecognizer.Builder(getApplicationContext()).build();
        textView = findViewById(R.id.resultTextView);

        Button uploadButton = findViewById(R.id.uploadButton);
        uploadButton.setOnClickListener(view -> openFileChooser());
        Button createPdfButton = findViewById(R.id.createPdfButton);
        createPdfButton.setOnClickListener(view -> createMyPDF(view));

        // Khởi tạo Text-to-Speech với tiếng Việt
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale vietnamese = new Locale("vi", "VN");
                int result = textToSpeech.setLanguage(vietnamese);

                if (result == TextToSpeech.LANG_MISSING_DATA) {
                    Toast.makeText(this, "Thiếu dữ liệu ngôn ngữ. Vui lòng tải gói tiếng Việt.", Toast.LENGTH_LONG).show();
                } else if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "Ngôn ngữ tiếng Việt không được hỗ trợ trên thiết bị này.", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Không thể khởi tạo Text-to-Speech", Toast.LENGTH_LONG).show();
            }
        });
        Button clearButton = findViewById(R.id.clearButton);
        clearButton.setOnClickListener(v -> clearText());
    }
    private void clearText() {
        textView.setText("");  // Xóa toàn bộ văn bản trong TextView
    }
    // Mở trình chọn tệp cho phép chọn nhiều tệp
    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimeTypes = {"image/*", "application/pdf"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // Cho phép chọn nhiều tệp
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE);
    }

    // Xử lý kết quả trả về từ trình chọn tệp
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri fileUri = data.getClipData().getItemAt(i).getUri();
                    handleFile(fileUri); // Xử lý từng tệp
                }
            } else {
                Uri fileUri = data.getData();
                if (fileUri != null) {
                    handleFile(fileUri);
                }
            }
        }
    }

    // Xử lý tệp ảnh hoặc PDF
    private void handleFile(@NonNull Uri fileUri) {
        String mimeType = getContentResolver().getType(fileUri);

        if (mimeType != null) {
            if (mimeType.startsWith("image/")) {
                processImage(fileUri);
            } else if (mimeType.equals("application/pdf")) {
                processPdf(fileUri);
            } else {
                Toast.makeText(this, "File không được hỗ trợ", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Không thể xác định loại file", Toast.LENGTH_SHORT).show();
        }
    }

    // Xử lý tệp ảnh
    private void processImage(@NonNull Uri fileUri) {
        try (InputStream inputStream = getContentResolver().openInputStream(fileUri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

            if (bitmap != null && textRecognizer.isOperational()) {
                Frame frame = new Frame.Builder().setBitmap(bitmap).build();
                String detectedText = detectTextFromFrame(frame);

                if (!detectedText.isEmpty()) {
                    textView.append(detectedText + "\n\n");  // Thêm kết quả vào TextView hiện có
                } else {
                    Toast.makeText(this, "Không phát hiện được văn bản trong ảnh", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Không thể xử lý ảnh", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Lỗi khi đọc file ảnh", Toast.LENGTH_SHORT).show();
        }
    }

    // Xử lý tệp PDF
    private void processPdf(@NonNull Uri fileUri) {
        try (ParcelFileDescriptor fileDescriptor = getContentResolver().openFileDescriptor(fileUri, "r")) {
            if (fileDescriptor != null) {
                PdfRenderer pdfRenderer = new PdfRenderer(fileDescriptor);

                StringBuilder detectedText = new StringBuilder();


                for (int i = 0; i < pdfRenderer.getPageCount(); i++) {
                    PdfRenderer.Page page = pdfRenderer.openPage(i);

                    Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                    if (textRecognizer.isOperational()) {
                        Frame frame = new Frame.Builder().setBitmap(bitmap).build();
                        String textFromPage = detectTextFromFrame(frame);
                        if (!textFromPage.isEmpty()) {
                            detectedText.append("Trang ").append(i + 1).append(":\n").append(textFromPage).append("\n\n");
                        }
                    }

                    // Đóng trang hiện tại
                    page.close();
                }

                // Hiển thị toàn bộ văn bản được phát hiện
                if (detectedText.length() > 0) {
                    textView.append(detectedText.toString());
                } else {
                    Toast.makeText(this, "Không phát hiện được văn bản trong PDF", Toast.LENGTH_SHORT).show();
                }

                pdfRenderer.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Lỗi khi đọc file PDF", Toast.LENGTH_SHORT).show();
        }
    }

    // Nhận diện văn bản từ ảnh hoặc PDF
    private String detectTextFromFrame(@NonNull Frame frame) {
        StringBuilder stringBuilder = new StringBuilder();
        SparseArray<TextBlock> textBlocks = textRecognizer.detect(frame);

        for (int i = 0; i < textBlocks.size(); i++) {
            TextBlock textBlock = textBlocks.valueAt(i);
            if (textBlock != null && textBlock.getValue() != null) {
                stringBuilder.append(textBlock.getValue()).append("\n");
            }
        }

        return stringBuilder.toString();
    }

    // Đọc văn bản đã nhận diện
    private void readDetectedText() {
        String text = textView.getText().toString();
        if (text.isEmpty()) {
            Toast.makeText(this, "Không có văn bản để đọc", Toast.LENGTH_SHORT).show();
        } else {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // Tạo và lưu tệp PDF
    public void createMyPDF(View view) {
        // Phát âm thông báo PDF được tạo
        textToSpeech.speak("pdf is generated", TextToSpeech.QUEUE_FLUSH, null);
        Toast.makeText(getApplicationContext(), "PDF is generated", Toast.LENGTH_LONG).show();

        // Tạo một đối tượng PdfDocument mới
        PdfDocument myPdfDocument = new PdfDocument();

        // Cấu hình trang PDF (kích thước trang)
        PdfDocument.PageInfo myPageInfo = new PdfDocument.PageInfo.Builder(399, 660, 1).create();
        PdfDocument.Page myPage = myPdfDocument.startPage(myPageInfo);

        // Thiết lập Paint (thuộc tính vẽ văn bản)
        Paint myPaint = new Paint();
        myPaint.setColor(Color.BLACK);  // Màu chữ đen
        myPaint.setTextSize(14);  // Kích thước chữ

        // Lấy nội dung từ TextView
        String myString = textView.getText().toString();
        int x = 15, y = 40;  // Tọa độ bắt đầu vẽ văn bản

        // Vẽ văn bản lên trang PDF
        for (String line : myString.split("\n")) {
            myPage.getCanvas().drawText(line, x, y, myPaint);
            y += myPaint.descent() - myPaint.ascent();  // Cập nhật vị trí y cho dòng tiếp theo
        }

        // Hoàn tất trang PDF
        myPdfDocument.finishPage(myPage);

        // Lưu tệp PDF vào thư mục Documents
        File documentsDir = new File(Environment.getExternalStorageDirectory(), "Documents");
        if (!documentsDir.exists()) {
            documentsDir.mkdirs();  // Tạo thư mục nếu chưa có
        }

        File myFile = new File(documentsDir, "myPDFFile.pdf");

        // Kiểm tra xem tệp đã tồn tại chưa, nếu có thì tạo tệp mới với tên khác
        int i = 0;
        while (myFile.exists()) {
            i++;
            myFile = new File(documentsDir, "myPDFFile(" + i + ").pdf");
        }

        // Kiểm tra quyền và yêu cầu quyền ghi nếu cần (API >= 23)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                return;
            }
        }

        try {
            // Ghi dữ liệu vào tệp PDF
            myPdfDocument.writeTo(new FileOutputStream(myFile));

            // Thông báo cho người dùng biết tệp PDF đã được tạo thành công
            Toast.makeText(getApplicationContext(), "PDF is saved at: " + myFile.getPath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getApplicationContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            // Đảm bảo đóng PdfDocument sau khi hoàn tất
            myPdfDocument.close();
        }
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}

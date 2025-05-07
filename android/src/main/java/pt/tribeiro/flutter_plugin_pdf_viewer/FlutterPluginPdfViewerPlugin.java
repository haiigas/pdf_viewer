package pt.tribeiro.flutter_plugin_pdf_viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Environment;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * FlutterPluginPdfViewerPlugin
 */
public class FlutterPluginPdfViewerPlugin implements MethodChannel.MethodCallHandler, FlutterPlugin {
    private final Object initializationLock = new Object();
    private MethodChannel flutterChannel;
    private Context context;

    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private final Object pluginLocker = new Object();
    private final String filePrefix = "FlutterPluginPdfViewer";

    private void onAttachedToEngine(Context applicationContext, BinaryMessenger messenger) {
        synchronized (initializationLock) {
            if (flutterChannel != null) return;
            this.context = applicationContext;
            flutterChannel = new MethodChannel(messenger, "flutter_plugin_pdf_viewer");
            flutterChannel.setMethodCallHandler(this);
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        onAttachedToEngine(binding.getApplicationContext(), binding.getBinaryMessenger());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        context = null;
        if (flutterChannel != null) {
            flutterChannel.setMethodCallHandler(null);
            flutterChannel = null;
        }
    }

    @Override
    public void onMethodCall(final MethodCall call, final MethodChannel.Result result) {
        synchronized (pluginLocker) {
            if (backgroundHandler == null) {
                handlerThread = new HandlerThread("flutterPdfViewer", Process.THREAD_PRIORITY_BACKGROUND);
                handlerThread.start();
                backgroundHandler = new Handler(handlerThread.getLooper());
            }
        }
        final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
        backgroundHandler.post(() -> {
            switch (call.method) {
                case "getNumberOfPages":
                    final String numResult = getNumberOfPages((String) call.argument("filePath"));
                    mainThreadHandler.post(() -> result.success(numResult));
                    break;
                case "getPage":
                    final String pageResult = getPage((String) call.argument("filePath"),
                            (int) call.argument("pageNumber"));
                    mainThreadHandler.post(() -> result.success(pageResult));
                    break;
                case "clearCache":
                    clearCacheDir();
                    mainThreadHandler.post(() -> result.success("clearCache"));
                    break;
                default:
                    mainThreadHandler.post(result::notImplemented);
                    break;
            }
        });
    }

    private String getNumberOfPages(String filePath) {
        File pdf = new File(filePath);
        try {
            clearCacheDir();
            PdfRenderer renderer = new PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY));
            int pageCount = renderer.getPageCount();
            renderer.close();
            return String.format("%d", pageCount);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private boolean clearCacheDir() {
        try {
            File directory = context.getCacheDir();
            FilenameFilter myFilter = (dir, name) -> name.toLowerCase().startsWith(filePrefix.toLowerCase());
            File[] files = directory.listFiles(myFilter);
            if (files != null) {
                for (File file : files) file.delete();
            }
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    private String getFileNameFromPath(String name) {
        String filePath = name.substring(name.lastIndexOf('/') + 1);
        filePath = filePath.substring(0, filePath.lastIndexOf('.'));
        return String.format("%s-%s", filePrefix, filePath);
    }

    private String createTempPreview(Bitmap bmp, String name, int page) {
        String fileNameOnly = getFileNameFromPath(name);
        File file;
        try {
            String fileName = String.format("%s-%d.png", fileNameOnly, page);
            file = File.createTempFile(fileName, null, context.getCacheDir());
            FileOutputStream out = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return file.getAbsolutePath();
    }

    private String getPage(String filePath, int pageNumber) {
        File pdf = new File(filePath);
        try {
            PdfRenderer renderer = new PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY));
            int pageCount = renderer.getPageCount();
            if (pageNumber > pageCount) pageNumber = pageCount;
            PdfRenderer.Page page = renderer.openPage(--pageNumber);

            double width = context.getResources().getDisplayMetrics().densityDpi * page.getWidth();
            double height = context.getResources().getDisplayMetrics().densityDpi * page.getHeight();
            final double docRatio = width / height;

            width = 2048;
            height = (int) (width / docRatio);
            Bitmap bitmap = Bitmap.createBitmap((int) width, (int) height, Bitmap.Config.ARGB_8888);

            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            try {
                return createTempPreview(bitmap, filePath, pageNumber);
            } finally {
                page.close();
                renderer.close();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }
}

package com.waslora.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DownloadControlTest {
    public static void main(String[] args) throws Exception {
        DirectDownloader.DownloadControl control = new DirectDownloader.DownloadControl();
        control.pause();
        CountDownLatch resumed = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try { control.awaitRunning(); resumed.countDown(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        worker.start();
        check(!resumed.await(150, TimeUnit.MILLISECONDS), "a paused transfer must wait");
        control.resume();
        check(resumed.await(2, TimeUnit.SECONDS), "resume must release the transfer worker");
        worker.join(2000);
        check(!control.isPaused(), "resume must clear the paused state");
        System.out.println("Download pause/resume tests passed.");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

package ru.onebeattrue.cli;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;

public final class DesktopBrowser implements Browser {
    @Override
    public void open(URI target) throws IOException {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            throw new IOException("Нет доступа к браузеру.");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Открытие браузера недоступно.");
        }
        desktop.browse(target);
    }
}

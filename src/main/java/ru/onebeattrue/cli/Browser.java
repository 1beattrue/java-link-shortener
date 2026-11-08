package ru.onebeattrue.cli;

import java.io.IOException;
import java.net.URI;

@FunctionalInterface
public interface Browser {
    void open(URI target) throws IOException;
}

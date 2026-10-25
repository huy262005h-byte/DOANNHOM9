package com.mycompany.doancs.client;

import java.io.File;

/**
 * Lớp kế thừa FileWatcher để tương thích.
 */
public class AutoSyncWatcher extends FileWatcher {
    public AutoSyncWatcher(File directory, Runnable syncTrigger) {
        super(directory, syncTrigger);
    }
}

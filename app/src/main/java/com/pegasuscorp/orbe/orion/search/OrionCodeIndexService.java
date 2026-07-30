package com.pegasuscorp.orbe.orion.search;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.orion.OrionProjectStore;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Index JavaParser en arrière-plan pour le projet Orion actif uniquement.
 */
public final class OrionCodeIndexService {

    private static OrionCodeIndexService instance;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private OrionCodeIndexService() {}

    public static synchronized OrionCodeIndexService get() {
        if (instance == null) instance = new OrionCodeIndexService();
        return instance;
    }

    static synchronized void resetForTests() {
        instance = null;
    }

    public void scheduleIndexActiveProject(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        bg.execute(() -> indexActiveProject(app));
    }

    public void scheduleReindexFile(Context ctx, String projectName,
            String filename, String content) {
        if (ctx == null || !OrionCodeIndexer.isJavaFile(filename)) return;
        Context app = ctx.getApplicationContext();
        bg.execute(() -> reindexFile(app, projectName, filename, content));
    }

    public void schedulePurgeFile(Context ctx, String projectName, String filename) {
        if (ctx == null || !OrionCodeIndexer.isJavaFile(filename)) return;
        Context app = ctx.getApplicationContext();
        bg.execute(() -> purgeFile(app, projectName, filename));
    }

    public OrionCodeIndexer indexerFor(Context ctx, String projectName) {
        if (ctx == null || TextUtils.isEmpty(projectName)) return null;
        try {
            return new OrionCodeIndexer(
                    projectName,
                    EmbeddingEngine.get(ctx.getApplicationContext()),
                    new VectorStore(ctx.getApplicationContext()));
        } catch (Exception e) {
            return null;
        }
    }

    private void indexActiveProject(Context ctx) {
        try {
            OrionProjectStore store = OrionProjectStore.get(ctx);
            String project = store.getActiveProject();
            if (TextUtils.isEmpty(project)) return;
            List<OrionCodeIndexer.JavaFile> files = new ArrayList<>();
            for (OrionProjectStore.ProjectFile pf : store.getProjectFiles(project)) {
                if (pf == null || !OrionCodeIndexer.isJavaFile(pf.name)) continue;
                String content = store.readFile(pf.name);
                files.add(new OrionCodeIndexer.JavaFile(pf.name, content));
            }
            OrionCodeIndexer indexer = indexerFor(ctx, project);
            if (indexer != null) indexer.indexProject(files);
        } catch (Exception ignored) {
        }
    }

    private void reindexFile(Context ctx, String projectName, String filename, String content) {
        try {
            OrionCodeIndexer indexer = indexerFor(ctx, projectName);
            if (indexer == null) return;
            indexer.reindexFile(new OrionCodeIndexer.JavaFile(filename, content));
        } catch (Exception ignored) {
        }
    }

    private void purgeFile(Context ctx, String projectName, String filename) {
        try {
            OrionCodeIndexer indexer = indexerFor(ctx, projectName);
            if (indexer == null) return;
            indexer.purgeFile(filename);
        } catch (Exception ignored) {
        }
    }
}

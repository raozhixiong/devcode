package com.lobster.store;

import com.lobster.store.ReferenceStore.Reference;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 参考库内容加载器（FR-I3 深度）：按 kind 拉取可挂载库的实际内容。 */
public class ReferenceLoader {

    private final Function<String, String> cfg;

    public ReferenceLoader(Function<String, String> cfg) {
        this.cfg = cfg;
    }

    /** 返回参考库内容文本；失败时返回以 ERROR: 开头的说明。 */
    public String load(Reference ref, Path cacheDir) {
        try {
            return switch (ref.kind()) {
                case "local" -> loadLocal(ref.uri());
                case "url" -> loadUrl(ref.uri());
                case "git" -> loadGit(ref, cacheDir);
                default -> "ERROR: 未知参考库类型 " + ref.kind();
            };
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String loadLocal(String uri) throws IOException {
        Path p = Path.of(uri);
        if (Files.isDirectory(p)) {
            var sb = new StringBuilder("# 目录: ").append(uri).append("\n");
            try (var s = Files.list(p).limit(200)) {
                s.forEach(c -> sb.append("- ").append(c.getFileName()).append("\n"));
            }
            return sb.toString();
        }
        return Files.readString(p);
    }

    private String loadUrl(String uri) throws Exception {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(uri))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body() : "ERROR: HTTP " + r.statusCode();
    }

    private String loadGit(Reference ref, Path cacheDir) throws Exception {
        Path dir = cacheDir.resolve("refs").resolve(ref.id());
        if (!Files.exists(dir)) {
            List<String> cmd = List.of("git", "clone", "--depth", "1", ref.uri(), dir.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            int code = p.waitFor();
            if (code != 0) return "ERROR: clone 失败 " + new String(p.getInputStream().readAllBytes());
        }
        var sb = new StringBuilder("# git 参考库已检出: ").append(dir).append("\n");
        List<String> files = new ArrayList<>();
        try (var s = Files.walk(dir).limit(300)) {
            s.filter(Files::isRegularFile)
              .forEach(f -> files.add(dir.relativize(f).toString()));
        }
        files.forEach(f -> sb.append("- ").append(f).append("\n"));
        return sb.toString();
    }
}

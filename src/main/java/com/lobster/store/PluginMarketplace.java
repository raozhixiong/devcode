package com.lobster.store;

import java.util.List;

/** 插件市场目录（对齐 FR-H-4 市场）：静态示例目录，install 复用 PluginStore。 */
public class PluginMarketplace {

    public record CatalogEntry(String id, String name, String version, String source, String description) {}

    public List<CatalogEntry> catalog() {
        return List.of(
                new CatalogEntry("pm-summarizer", "summarizer", "1.0.0",
                        "https://market.lobster.dev/summarizer.tar.gz",
                        "会话摘要插件：自动生成会话摘要并写入记忆"),
                new CatalogEntry("pm-jira", "jira-connector", "0.9.2",
                        "https://market.lobster.dev/jira-connector.tar.gz",
                        "Jira 连接器：创建/查询工单"),
                new CatalogEntry("pm-translator", "translator", "1.2.0",
                        "https://market.lobster.dev/translator.tar.gz",
                        "多语言翻译插件：基于 LLM 的双向翻译")
        );
    }

    public CatalogEntry get(String catalogId) {
        return catalog().stream().filter(e -> e.id().equals(catalogId)).findFirst().orElse(null);
    }
}

package com.example.ui5_generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@CrossOrigin(origins = "*")
public class UI5GeneratorController {

    @Value("${nvidia.api.key}")
    private String nvidiaApiKey;

    @Value("${nvidia.model}")
    private String nvidiaModel;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.model}")
    private String geminiModel;

    // ─────────────────────────────────────────────
    // CDN URL — the only correct OpenUI5 CDN URL
    // ─────────────────────────────────────────────
    private static final String CDN_URL =
        "https://openui5.hana.ondemand.com" +
        "/resources/sap-ui-core.js";

    // ─────────────────────────────────────────────
    // HTML SANITIZER
    // Fixes the most common AI mistakes in HTML:
    // 1. Relative CDN path → absolute CDN URL
    // 2. Missing frameOptions → adds allow
    // 3. Wrong CDN URL variations → correct URL
    // This runs on ALL generated HTML before
    // returning to frontend — both Gemini and Llama
    // ─────────────────────────────────────────────
    private String sanitizeHtml(String html) {
        if (html == null || html.isEmpty())
            return html;


        String trimmed = html.trim();
    if (trimmed.startsWith("```xml")
            || trimmed.startsWith("```")
            || trimmed.toLowerCase()
               .contains("<mvc:view")) {
        System.out.println(
            "SANITIZER: detected XML in preview " +
            "— rejecting and returning error");
        return
            "<!DOCTYPE html><html><body>" +
            "<h2 style='color:orange;" +
            "padding:20px;font-family:Arial'>" +
            "Preview generation issue — " +
            "please try again</h2>" +
            "<p style='padding:20px;" +
            "font-family:Arial'>" +
            "Gemini returned XML instead of HTML. " +
            "Regenerating...</p>" +
            "</body></html>";
    }

        // Fix 1: Replace ANY variation of sap-ui-core
        // src that is not the correct full CDN URL
        // Covers: relative paths, wrong CDN URLs,
        // missing https, etc.

        // Pattern: src="...sap-ui-core.js..."
        // Replace with correct CDN URL
        html = html.replaceAll(
            "src=[\"'][^\"']*sap-ui-core\\.js[^\"']*[\"']",
            "src=\"" + CDN_URL + "\""
        );

        // Fix 2: Add frameOptions=allow if missing
        // This prevents FrameOptions DENY blocking
        if (!html.contains("frameOptions")) {
            html = html.replace(
                "id=\"sap-ui-bootstrap\"",
                "id=\"sap-ui-bootstrap\" " +
                "data-sap-ui-frameOptions=\"allow\""
            );
            // Also try single quotes
            html = html.replace(
                "id='sap-ui-bootstrap'",
                "id='sap-ui-bootstrap' " +
                "data-sap-ui-frameOptions='allow'"
            );
        }

        // Fix 3: Ensure libs always has all 3
        if (!html.contains("sap.ui.layout")) {
            html = html.replace(
                "data-sap-ui-libs=\"sap.m\"",
                "data-sap-ui-libs=" +
                "\"sap.m,sap.ui.layout,sap.f\""
            );
            html = html.replace(
                "data-sap-ui-libs=\"sap.m,sap.f\"",
                "data-sap-ui-libs=" +
                "\"sap.m,sap.ui.layout,sap.f\""
            );
        }

        System.out.println(
            "HTML sanitized successfully");
        return html;
    }

    // ─────────────────────────────────────────────
    // PREVIEW SYSTEM PROMPT — Llama
    // ─────────────────────────────────────────────
    private static final String PREVIEW_SYSTEM =
        "You are a strict SAP UI5 expert developer. " +
        "ONLY generate SAP UI5 using OpenUI5.\n\n" +

        "OUTPUT: Raw HTML only starting with " +
        "<!DOCTYPE html>. No markdown. " +
        "No explanations.\n\n" +

        "BOOTSTRAP — use EXACTLY this script tag:\n" +
        "<script id=\"sap-ui-bootstrap\"\n" +
        "  src=\"https://openui5.hana.ondemand.com" +
        "/resources/sap-ui-core.js\"\n" +
        "  data-sap-ui-theme=\"sap_horizon\"\n" +
        "  data-sap-ui-compatVersion=\"edge\"\n" +
        "  data-sap-ui-libs=" +
        "\"sap.m,sap.ui.layout,sap.f\"\n" +
        "  data-sap-ui-frameOptions=\"allow\">\n" +
        "</script>\n\n" +

        "Always use sap.ui.require() for components.\n"+
        "Always <div id='content'></div> in body.\n" +
        "Always .placeAt('content') at end.\n\n" +

        "HEADER — NEVER ShellBar. Use sap.m.Bar:\n" +
        "  new sap.m.Page({\n" +
        "    customHeader: new sap.m.Bar({\n" +
        "      contentMiddle: [\n" +
        "        new sap.m.Title({text:'Title'})\n" +
        "      ],\n" +
        "      contentRight: [\n" +
        "        new sap.m.Avatar({\n" +
        "          initials:'KV',\n" +
        "          displayShape:'Circle'\n" +
        "        })\n" +
        "      ]\n" +
        "    })\n" +
        "  })\n\n" +

        "TILES — GenericTile exact pattern:\n" +
        "  new sap.m.GenericTile({\n" +
        "    header: 'Revenue',\n" +
        "    subheader: 'This month',\n" +
        "    tileContent: [\n" +
        "      new sap.m.TileContent({\n" +
        "        content: new sap.m.NumericContent({\n"+
        "          value: '120K',\n" +
        "          icon: 'sap-icon://money-coins'\n" +
        "        })\n" +
        "      })\n" +
        "    ]\n" +
        "  })\n" +
        "  Wrap in: new sap.m.FlexBox({\n" +
        "    wrap:'Wrap', items:[...]\n" +
        "  })\n\n" +

        "NEVER: GenericTileContent, TileContainer,\n" +
        "sap/f/GenericTile, unit on NumericContent,\n" +
        "VerticalBox, HorizontalBox, sap/m/Icon,\n" +
        "sap/m/SimpleForm, sap/ui/layout/form/Label.\n\n"+

        "SimpleForm: 'sap/ui/layout/form/SimpleForm'\n" +
        "Label: 'sap/m/Label'\n" +
        "VBox: 'sap/m/VBox', HBox: 'sap/m/HBox'\n\n" +

        "Valid icons: sap-icon://money-coins " +
        "sap-icon://sales-order sap-icon://customer " +
        "sap-icon://settings sap-icon://bell " +
        "sap-icon://person-placeholder sap-icon://add " +
        "sap-icon://edit sap-icon://delete " +
        "sap-icon://search sap-icon://home\n\n" +

        "JSONModel for table data. " +
        "sap.m.App+Pages for large pages. " +
        "IconTabBar for tabs. " +
        "MessageToast+Dialog for submit.\n";

    // ─────────────────────────────────────────────
    // STRUCTURE SYSTEM PROMPT — Llama
    // ─────────────────────────────────────────────
    private static final String STRUCTURE_SYSTEM =
        "You are a SAP UI5 project structure expert.\n"+
        "Convert SAP UI5 HTML into project files.\n\n" +
        "Output exactly 5 sections:\n" +
        "===MANIFEST===\n===VIEW===\n" +
        "===CONTROLLER===\n===COMPONENT===\n" +
        "===INDEX===\n\n" +
        "Never use markdown. Never add explanations.\n\n"+
        "===MANIFEST===: Complete manifest.json.\n" +
        "sap.app id=myapp. sap.ui5 rootView " +
        "name=myapp.view.App type=XML async=true.\n\n" +
        "===VIEW===: Complete App.view.xml.\n" +
        "mvc:View root. NEVER ShellBar.\n\n" +
        "===CONTROLLER===: Complete App.controller.js\n"+
        "sap.ui.define Controller.extend.\n" +
        "Namespace: myapp.controller.App.\n\n" +
        "===COMPONENT===: Complete Component.js.\n" +
        "Extends UIComponent namespace myapp.\n\n" +
        "===INDEX===: Complete index.html.\n" +
        "ComponentSupport bootstrap.\n";

    // ─────────────────────────────────────────────
    // GEMINI SYSTEM PROMPT — short and focused
    // ─────────────────────────────────────────────
    private static final String GEMINI_SYSTEM =
        "You are a strict SAP UI5 expert developer.\n" +
        "Generate SAP UI5 code only.\n\n" +

        "CRITICAL BOOTSTRAP — use EXACTLY this:\n" +
        "<script id=\"sap-ui-bootstrap\"\n" +
        "  src=\"https://openui5.hana.ondemand.com" +
        "/resources/sap-ui-core.js\"\n" +
        "  data-sap-ui-theme=\"sap_horizon\"\n" +
        "  data-sap-ui-compatVersion=\"edge\"\n" +
        "  data-sap-ui-libs=" +
        "\"sap.m,sap.ui.layout,sap.f\"\n" +
        "  data-sap-ui-frameOptions=\"allow\">\n" +
        "</script>\n\n" +

        "NEVER: ShellBar, GenericTileContent, " +
        "TileContainer, sap/f/GenericTile, " +
        "unit on NumericContent, VerticalBox, " +
        "HorizontalBox, sap/m/Icon separately, " +
        "sap/m/SimpleForm, sap/ui/layout/form/Label, " +
        "relative paths for OpenUI5.\n\n" +

        "USE INSTEAD:\n" +
        "- sap.m.Bar for headers\n" +
        "- sap/m/GenericTile for tiles\n" +
        "- sap/ui/layout/form/SimpleForm for forms\n" +
        "- sap/m/Label for labels\n" +
        "- sap/m/VBox and sap/m/HBox for layout\n\n" +

        "Header pattern:\n" +
        "  new sap.m.Page({\n" +
        "    customHeader: new sap.m.Bar({\n" +
        "      contentMiddle: [\n" +
        "        new sap.m.Title({text:'Title'})\n" +
        "      ],\n" +
        "      contentRight: [\n" +
        "        new sap.m.Avatar({\n" +
        "          initials:'KV',\n" +
        "          displayShape:'Circle'\n" +
        "        })\n" +
        "      ]\n" +
        "    })\n" +
        "  })\n\n" +

        "Tile pattern:\n" +
        "  new sap.m.GenericTile({\n" +
        "    header:'Revenue',\n" +
        "    tileContent:[new sap.m.TileContent({\n" +
        "      content:new sap.m.NumericContent({\n" +
        "        value:'120K',\n" +
        "        icon:'sap-icon://money-coins'\n" +
        "      })\n" +
        "    })]\n" +
        "  })\n";

    // ─────────────────────────────────────────────
    // Call NVIDIA Llama API
    // ─────────────────────────────────────────────
    private String callNvidia(
            String systemPrompt,
            String userPrompt,
            int maxTokens) throws Exception {

        List<Map<String, String>> messages =
                new ArrayList<>();

        Map<String, String> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        Map<String, Object> body = new HashMap<>();
        body.put("model", nvidiaModel);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.1);
        body.put("top_p", 0.9);
        body.put("stream", false);

        ObjectMapper mapper = new ObjectMapper();
        String jsonBody =
                mapper.writeValueAsString(body);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(
                MediaType.APPLICATION_JSON);
        headers.set("Authorization",
                "Bearer " + nvidiaApiKey);

        String url =
            "https://integrate.api.nvidia.com" +
            "/v1/chat/completions";

        RestTemplate rest = new RestTemplate();
        HttpEntity<String> entity =
                new HttpEntity<>(jsonBody, headers);

        System.out.println("Calling NVIDIA Llama...");

        ResponseEntity<String> response =
                rest.postForEntity(
                url, entity, String.class);

        System.out.println("NVIDIA Status: "
                + response.getStatusCode());

        Map responseMap = mapper.readValue(
                response.getBody(), Map.class);
        List choices =
                (List) responseMap.get("choices");
        Map firstChoice = (Map) choices.get(0);
        Map message =
                (Map) firstChoice.get("message");
        String result =
                message.get("content").toString();

        return cleanResult(result);
    }

    // ─────────────────────────────────────────────
    // Call Gemini API
    // ─────────────────────────────────────────────
    private String callGemini(
            String systemPrompt,
            String userPrompt,
            int maxTokens) throws Exception {

        List<Map<String, Object>> contents =
                new ArrayList<>();

        // System as first user turn
        Map<String, Object> systemTurn =
                new HashMap<>();
        systemTurn.put("role", "user");
        systemTurn.put("parts",
            List.of(Map.of("text", systemPrompt)));
        contents.add(systemTurn);

        // Model acknowledges
        Map<String, Object> modelAck =
                new HashMap<>();
        modelAck.put("role", "model");
        modelAck.put("parts", List.of(Map.of("text",
            "Understood. I will follow all rules. " +
            "For PREVIEW section I will output " +
            "complete standalone HTML with the exact " +
            "bootstrap script including full CDN URL " +
            "and frameOptions=allow.")));
        contents.add(modelAck);

        // Actual user request
        Map<String, Object> userTurn =
                new HashMap<>();
        userTurn.put("role", "user");
        userTurn.put("parts",
            List.of(Map.of("text", userPrompt)));
        contents.add(userTurn);

        Map<String, Object> genConfig =
                new HashMap<>();
        genConfig.put("maxOutputTokens", maxTokens);
        genConfig.put("temperature", 0.1);

        Map<String, Object> body = new HashMap<>();
        body.put("contents", contents);
        body.put("generationConfig", genConfig);

        ObjectMapper mapper = new ObjectMapper();
        String jsonBody =
                mapper.writeValueAsString(body);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(
                MediaType.APPLICATION_JSON);

        String url =
            "https://generativelanguage.googleapis" +
            ".com/v1beta/models/" + geminiModel +
            ":generateContent?key=" + geminiApiKey;

        RestTemplate rest = new RestTemplate();
        HttpEntity<String> entity =
                new HttpEntity<>(jsonBody, headers);

        System.out.println("Calling Gemini...");

        ResponseEntity<String> response =
                rest.postForEntity(
                url, entity, String.class);

        System.out.println("Gemini Status: "
                + response.getStatusCode());

        Map responseMap = mapper.readValue(
                response.getBody(), Map.class);
        List candidates =
                (List) responseMap.get("candidates");
        Map firstCandidate =
                (Map) candidates.get(0);
        Map content =
                (Map) firstCandidate.get("content");
        List parts = (List) content.get("parts");
        Map firstPart = (Map) parts.get(0);
        String result =
                firstPart.get("text").toString();

        return cleanResult(result);
    }

    // ─────────────────────────────────────────────
    // Clean markdown fences
    // ─────────────────────────────────────────────
    private String cleanResult(String result) {
        result = result.trim();
        if (result.startsWith("```")) {
            int firstNewLine = result.indexOf("\n");
            int lastBacktick =
                    result.lastIndexOf("```");
            if (firstNewLine != -1
                    && lastBacktick > firstNewLine) {
                result = result.substring(
                        firstNewLine + 1,
                        lastBacktick).trim();
            }
        }
        System.out.println("Response length: "
                + result.length());
        System.out.println("Starts: " +
                result.substring(
                0, Math.min(80, result.length())));
        return result;
    }

    // ─────────────────────────────────────────────
    // Extract section between two markers
    // ─────────────────────────────────────────────
    private String extractSection(
            String text,
            String startMarker,
            String endMarker) {

        int start = text.indexOf(startMarker);
        if (start == -1) {
            System.out.println(
                "WARNING: marker not found: "
                + startMarker);
            return "";
        }
        start += startMarker.length();

        if (start < text.length()
                && text.charAt(start) == '\n') {
            start++;
        } else if (start < text.length() - 1
                && text.charAt(start) == '\r'
                && text.charAt(start + 1) == '\n') {
            start += 2;
        }

        if (endMarker == null) {
            return text.substring(start).trim();
        }

        int end = text.indexOf(endMarker, start);
        if (end == -1) {
            return text.substring(start).trim();
        }

        return text.substring(start, end).trim();
    }

    // ─────────────────────────────────────────────
    // Sanitize user prompt — replace ShellBar
    // ─────────────────────────────────────────────
    private String sanitizePrompt(String prompt) {
        if (prompt == null) return "";
        prompt = prompt.replace(
            "sap.f.ShellBar", "sap.m.Bar");
        prompt = prompt.replace(
            "ShellBar", "sap.m.Bar header");
        prompt = prompt.replace(
            "shellbar", "sap.m.Bar header");
        return prompt;
    }

    // ─────────────────────────────────────────────
    // Build Gemini user prompt with markers
    // ─────────────────────────────────────────────
    private String buildGeminiPrompt(
            String userRequirement,
            String previousHtml) {

        String req;
        if (previousHtml != null
                && !previousHtml.isEmpty()) {
            req =
                "EXISTING PAGE:\n" + previousHtml +
                "\n\nMODIFY BASED ON:\n" +
                userRequirement;
        } else {
            req = userRequirement;
        }

        return
            "Generate SAP UI5 project for: " +
            req + "\n\n" +

            "OUTPUT EXACTLY 6 SECTIONS:\n\n" +

            "===PREVIEW===\n" +
            "MUST be complete standalone HTML.\n" +
            "MUST start with <!DOCTYPE html>.\n" +
            "MUST use this EXACT bootstrap:\n" +
            "<script id=\"sap-ui-bootstrap\"\n" +
            "  src=\"https://openui5.hana.ondemand" +
            ".com/resources/sap-ui-core.js\"\n" +
            "  data-sap-ui-theme=\"sap_horizon\"\n" +
            "  data-sap-ui-compatVersion=\"edge\"\n" +
            "  data-sap-ui-libs=" +
            "\"sap.m,sap.ui.layout,sap.f\"\n" +
            "  data-sap-ui-frameOptions=\"allow\">\n" +
            "</script>\n" +
            "MUST use sap.ui.require().\n" +
            "MUST have div id=content.\n" +
            "MUST call .placeAt('content').\n" +
            "NEVER ComponentSupport here.\n" +
            "NEVER data-sap-ui-oninit here.\n" +
            "NEVER relative paths for OpenUI5.\n\n" +

            "===MANIFEST===\n" +
            "Complete manifest.json.\n" +
            "sap.app id=myapp type=application.\n" +
            "sap.ui5 rootView name=myapp.view.App " +
            "type=XML async=true.\n\n" +

            "===VIEW===\n" +
            "Complete App.view.xml.\n" +
            "Start with <mvc:View.\n" +
            "All namespaces included.\n" +
            "controllerName=myapp.controller.App.\n\n"+

            "===CONTROLLER===\n" +
            "Complete App.controller.js.\n" +
            "sap.ui.define Controller.extend.\n" +
            "Namespace: myapp.controller.App.\n\n" +

            "===COMPONENT===\n" +
            "Complete Component.js.\n" +
            "Extends UIComponent namespace myapp.\n\n" +

            "===INDEX===\n" +
            "Complete index.html.\n" +
            "ComponentSupport bootstrap.\n" +
            "data-sap-ui-oninit=" +
            "module:sap/ui/core/ComponentSupport.\n";
    }

    // ─────────────────────────────────────────────
    // Validate preview is proper HTML
    // ─────────────────────────────────────────────
    private boolean isValidPreviewHtml(String html) {
    if (html == null || html.isEmpty())
        return false;
    String t = html.trim().toLowerCase();
    // Must start like HTML
    boolean startsLikeHtml =
        t.startsWith("<!doctype")
        || t.startsWith("<html");
    // Must NOT look like XML view
    boolean looksLikeXml =
        t.contains("<mvc:view")
        || t.contains("controllername=")
        || t.startsWith("```xml")
        || t.startsWith("```");
    return startsLikeHtml && !looksLikeXml;
}

    // ─────────────────────────────────────────────
    // POST /generate
    // ─────────────────────────────────────────────
    @PostMapping(
        value = "/generate",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>>
            generate(
            @RequestBody
            Map<String, String> request) {

        Map<String, Object> result = new HashMap<>();
        try {
            String userPrompt =
                    request.get("prompt");
            String previousHtml =
                    request.get("previousHtml");
            String selectedModel =
                    request.get("model");

            if (previousHtml == null)
                previousHtml = "";
            if (selectedModel == null)
                selectedModel = "llama";

            // Sanitize prompt
            userPrompt = sanitizePrompt(userPrompt);

            System.out.println(
                "=== /generate called ===");
            System.out.println(
                "Model: " + selectedModel);
            System.out.println(
                "Prompt: " + userPrompt);

            if ("gemini".equals(selectedModel)) {

                System.out.println(
                    "Using Gemini single call...");

                String geminiPrompt =
                    buildGeminiPrompt(
                        userPrompt, previousHtml);

                String raw = callGemini(
                        GEMINI_SYSTEM,
                        geminiPrompt, 16000);

                System.out.println(
                    "Gemini raw length: "
                    + raw.length());

                String previewHtml = extractSection(
                    raw, "===PREVIEW===",
                    "===MANIFEST===");
                String manifest = extractSection(
                    raw, "===MANIFEST===",
                    "===VIEW===");
                String view = extractSection(
                    raw, "===VIEW===",
                    "===CONTROLLER===");
                String controller = extractSection(
                    raw, "===CONTROLLER===",
                    "===COMPONENT===");
                String component = extractSection(
                    raw, "===COMPONENT===",
                    "===INDEX===");
                String index = extractSection(
                    raw, "===INDEX===", null);

                System.out.println(
                    "preview: " + previewHtml.length()
                    + " manifest: " + manifest.length()
                    + " view: " + view.length());

                // Validate preview
                if (!isValidPreviewHtml(previewHtml)) {
                    System.out.println(
                        "Invalid preview — " +
                        "falling back to Llama...");
                    String llamaPrompt =
                        "Generate SAP UI5 page:\n\n"
                        + userPrompt;
                    previewHtml = callNvidia(
                        PREVIEW_SYSTEM,
                        llamaPrompt, 16000);
                }

                // SANITIZE the preview HTML
                // Fixes CDN URL, frameOptions, libs
                previewHtml = sanitizeHtml(previewHtml);

                Map<String, String> files =
                        new HashMap<>();
                files.put("manifest", manifest);
                files.put("view", view);
                files.put("controller", controller);
                files.put("component", component);
                files.put("index", index);

                boolean hasFiles =
                    !manifest.isEmpty()
                    || !view.isEmpty();

                result.put("success", true);
                result.put("html", previewHtml);
                result.put("files",
                    hasFiles ? files : null);
                result.put("model", "gemini");

            } else {

                System.out.println(
                    "Using Llama preview call...");

                String fullPrompt;
                if (previousHtml.isEmpty()) {
                    fullPrompt =
                        "Generate a complete SAP " +
                        "UI5 page. Output only " +
                        "raw HTML:\n\n" + userPrompt;
                } else {
                    fullPrompt =
                        "Existing page:\n\n"
                        + previousHtml +
                        "\n\nModify based on:\n\n"
                        + userPrompt;
                }

                String html = callNvidia(
                        PREVIEW_SYSTEM,
                        fullPrompt, 16000);

                // SANITIZE Llama HTML too
                html = sanitizeHtml(html);

                System.out.println(
                    "Llama preview length: "
                    + html.length());

                result.put("success", true);
                result.put("html", html);
                result.put("files", null);
                result.put("model", "llama");
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.out.println(
                "Generate error: "
                + e.getMessage());
            e.printStackTrace();
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("html",
                "<!DOCTYPE html><html><body>" +
                "<h2 style='color:red;" +
                "padding:20px'>" +
                "Error: " + e.getMessage() +
                "</h2></body></html>");
            return ResponseEntity.ok(result);
        }
    }

    // ─────────────────────────────────────────────
    // POST /structure — Llama only, lazy load
    // ─────────────────────────────────────────────
    @PostMapping(
        value = "/structure",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>>
            structure(
            @RequestBody
            Map<String, String> request) {

        Map<String, Object> result = new HashMap<>();
        try {
            String html = request.get("html");

            System.out.println(
                "=== /structure called ===");
            System.out.println(
                "HTML length: " + html.length());

            String userPrompt =
                "Convert this SAP UI5 HTML into " +
                "5 structured project files:\n\n"
                + html;

            String raw = callNvidia(
                    STRUCTURE_SYSTEM,
                    userPrompt, 16000);

            String manifest = extractSection(
                raw, "===MANIFEST===", "===VIEW===");
            String view = extractSection(
                raw, "===VIEW===",
                "===CONTROLLER===");
            String controller = extractSection(
                raw, "===CONTROLLER===",
                "===COMPONENT===");
            String component = extractSection(
                raw, "===COMPONENT===",
                "===INDEX===");
            String index = extractSection(
                raw, "===INDEX===", null);

            System.out.println(
                "manifest: " + manifest.length()
                + " view: " + view.length());

            if (manifest.isEmpty()
                    && view.isEmpty()) {
                result.put("success", false);
                result.put("error",
                    "Structure failed. Try again.");
                return ResponseEntity.ok(result);
            }

            Map<String, String> files =
                    new HashMap<>();
            files.put("manifest", manifest);
            files.put("view", view);
            files.put("controller", controller);
            files.put("component", component);
            files.put("index", index);

            result.put("success", true);
            result.put("files", files);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.out.println(
                "Structure error: "
                + e.getMessage());
            e.printStackTrace();
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
    }
}
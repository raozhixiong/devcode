package com.lobster.ws.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.auth.AuthService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/** 设备配对 RPC（M5-27）。 */
@Component
public class DeviceRpc extends BaseRpc {

    private final AuthService authService;

    public DeviceRpc(AuthService authService) { this.authService = authService; }

    @Override
    public Set<String> methods() {
        return Set.of("device.pair.request", "device.pair.approve", "device.pair.reject",
                "device.pair.status", "device.list", "device.revoke", "device.rename");
    }

    @Override
    public void handle(WebSocketSession session, String id, String method, JsonNode params, RpcContext ctx) throws Exception {
        this.session = session; this.ctx = ctx;
        switch (method) {
            case "device.pair.request" -> pairRequest(id, params);
            case "device.pair.approve" -> pairApprove(id, params);
            case "device.pair.reject" -> pairReject(id, params);
            case "device.pair.status" -> pairStatus(id);
            case "device.list" -> list(id);
            case "device.revoke" -> revoke(id, params);
            case "device.rename" -> rename(id, params);
        }
    }

    private void pairRequest(String id, JsonNode params) {
        var pairing = authService.devices().createPairing(params.path("label").asText(null),
                params.path("publicKey").asText(""), params.path("platform").asText(null),
                params.path("scopes").asText("read"));
        sendRes(id, true, on().put("pairingId", pairing.id()).put("status", pairing.status()));
    }

    private void pairApprove(String id, JsonNode params) {
        String pairingId = params.path("pairingId").asText();
        if (pairingId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "pairingId 必填"));
            return;
        }
        var device = authService.devices().resolvePairing(pairingId, true, params.path("label").asText(null),
                params.path("publicKey").asText(""), params.path("platform").asText(null),
                params.path("role").asText("developer"));
        if (device.isEmpty()) {
            sendRes(id, false, on().put("code", "NOT_FOUND").put("message", "配对请求不存在或已处理"));
            return;
        }
        sendRes(id, true, on().put("deviceId", device.get().id()).put("status", "approved"));
    }

    private void pairReject(String id, JsonNode params) {
        String pairingId = params.path("pairingId").asText();
        if (pairingId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "pairingId 必填"));
            return;
        }
        authService.devices().resolvePairing(pairingId, false, null, null, null, null);
        sendRes(id, true, on().put("pairingId", pairingId).put("status", "rejected"));
    }

    private void pairStatus(String id) {
        ArrayNode arr = (ArrayNode) ctx.om().valueToTree(authService.devices().listPendingPairings());
        sendRes(id, true, on().set("pending", arr));
    }

    private void list(String id) {
        ArrayNode arr = (ArrayNode) ctx.om().valueToTree(authService.devices().list());
        sendRes(id, true, on().set("devices", arr));
    }

    private void revoke(String id, JsonNode params) {
        String deviceId = params.path("deviceId").asText();
        if (deviceId.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "deviceId 必填"));
            return;
        }
        authService.devices().revoke(deviceId);
        sendRes(id, true, on().put("deviceId", deviceId));
    }

    private void rename(String id, JsonNode params) {
        String deviceId = params.path("deviceId").asText();
        String label = params.path("label").asText();
        if (deviceId.isEmpty() || label.isEmpty()) {
            sendRes(id, false, on().put("code", "BAD_REQUEST").put("message", "deviceId 和 label 必填"));
            return;
        }
        authService.devices().rename(deviceId, label);
        sendRes(id, true, on().put("deviceId", deviceId).put("label", label));
    }
}

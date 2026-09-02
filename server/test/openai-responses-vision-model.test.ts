import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { OpenAIResponsesVisionModel } from "../src/extraction/openai-responses-vision-model.js";

describe("OpenAI Responses vision adapter", () => {
  it("sends a non-stored multimodal structured-output request and injects trusted trace metadata", async () => {
    let request: RequestInit | undefined;
    const model = new OpenAIResponsesVisionModel({
      apiKey: "secret-key",
      model: "configured-model",
      fetch: async (_url, init) => {
        request = init;
        return Response.json(responseBody(validWireOutput()));
      },
    });

    const output = await model.analyze({
      image: Uint8Array.from([1, 2, 3]),
      contentType: "image/png",
      supplementalText: "这是客户陈先生",
    }) as Record<string, any>;

    const body = JSON.parse(String(request?.body));
    assert.equal(new Headers(request?.headers).get("user-agent"), "ThreadMind/0.1.0");
    assert.equal(body.model, "configured-model");
    assert.equal(body.store, false);
    assert.equal(body.text.format.type, "json_schema");
    assert.equal(body.text.format.strict, true);
    assert.equal(body.text.format.schema.additionalProperties, false);
    assert.equal(body.input[0].role, "developer");
    assert.match(body.input[0].content[0].text, /untrusted data/);
    assert.match(body.input[0].content[0].text, /Every evidenceRefs entry must reference an existing evidenceSpans\[\]\.id/);
    assert.equal(body.input[1].role, "user");
    assert.equal(body.input[1].content[0].image_url, "data:image/png;base64,AQID");
    assert.match(body.input[1].content[1].text, /这是客户陈先生/);
    assert.equal(body.input[0].content[0].text.includes("这是客户陈先生"), false);
    assert.equal(JSON.stringify(body).includes("secret-key"), false);
    assert.deepEqual(output.actionCandidates[0].fields, { displayName: "陈先生", contactMethod: "chen@example.com" });
    assert.deepEqual(output.actionCandidates[0].fieldConfidence, { displayName: 0.95, contactMethod: 0.99 });
    assert.equal(output.modelTrace.model, "configured-model");
    assert.equal(output.modelTrace.promptVersion, "threadmind-extraction-v3");
  });

  it("rejects provider failures without exposing the response body", async () => {
    const model = new OpenAIResponsesVisionModel({
      apiKey: "secret-key",
      model: "configured-model",
      fetch: async () => new Response("private provider detail", { status: 429 }),
    });
    await assert.rejects(
      model.analyze({ image: Uint8Array.from([1]), contentType: "image/jpeg" }),
      (error: any) => error.code === "model_provider_http_429" && !error.message.includes("private provider detail"),
    );
  });

  it("rejects missing output text and malformed structured JSON", async () => {
    const missing = new OpenAIResponsesVisionModel({
      apiKey: "secret-key",
      model: "configured-model",
      fetch: async () => Response.json({ status: "completed", output: [] }),
    });
    await assert.rejects(missing.analyze({ image: Uint8Array.from([1]), contentType: "image/webp" }), { code: "invalid_model_response" });

    const malformed = new OpenAIResponsesVisionModel({
      apiKey: "secret-key",
      model: "configured-model",
      fetch: async () => Response.json(responseBody("not-json")),
    });
    await assert.rejects(malformed.analyze({ image: Uint8Array.from([1]), contentType: "image/webp" }), { code: "invalid_model_json" });
  });
});

function responseBody(output: unknown) {
  return {
    status: "completed",
    error: null,
    incomplete_details: null,
    output: [{ type: "message", content: [{ type: "output_text", text: typeof output === "string" ? output : JSON.stringify(output) }] }],
  };
}

function validWireOutput() {
  return {
    messages: [{ id: "m1", order: 0, text: "陈先生 chen@example.com", speaker: "对方", confidence: 0.99, region: null }],
    participants: [{ id: "p1", displayName: "陈先生", evidenceRefs: ["e1"], confidence: 0.95 }],
    entities: [{ id: "entity1", type: "email", value: "chen@example.com", evidenceRefs: ["e1"], confidence: 0.99 }],
    temporalExpressions: [],
    actionCandidates: [{
      id: "action1",
      type: "create_contact",
      fieldValues: [
        { name: "displayName", value: "陈先生", confidence: 0.95 },
        { name: "contactMethod", value: "chen@example.com", confidence: 0.99 },
      ],
      evidenceRefs: ["e1"],
      validationIssues: ["missing_target_account"],
      targetAccountId: null,
    }],
    memoryCandidates: [{
      id: "memory1",
      subjectRefs: ["p1"],
      type: "profile",
      assertion: "陈先生的邮箱是 chen@example.com",
      epistemicStatus: "fact",
      confidence: 0.99,
      sensitivity: "normal",
      evidenceRefs: ["e1"],
    }],
    evidenceSpans: [{ id: "e1", messageId: "m1", excerpt: "陈先生 chen@example.com", confidence: 0.99 }],
    warnings: [],
  };
}

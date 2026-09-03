import assert from "node:assert/strict";
import { it } from "node:test";
import { OpenAIResponsesInsightModel, createInsightGenerator } from "../src/insight/openai-responses-insight-model.ts";
import { EvidenceBackedInsightGenerator } from "../src/insight/insight-generator.ts";
import { GroundedInsightGenerator, type InsightSynthesisInput } from "../src/insight/grounded-insight-generator.ts";

const input: InsightSynthesisInput = {
  action: { type: "create_contact", fields: { displayName: "Synthetic contact" } },
  currentContext: null, premises: [], contactContext: {
    source: "android_contacts_provider", capturedAt: "2026-09-04T00:00:00Z", permissionStatus: "unavailable", queries: [], records: [],
  },
};

it("sends non-stored structured synthesis and returns raw output for evidence validation", async () => {
  const model = new OpenAIResponsesInsightModel({ apiKey: "test-key", model: "test-model", baseUrl: "https://example.invalid/v1/",
    fetch: async (url, init) => {
      assert.equal(url, "https://example.invalid/v1/responses");
      const body = JSON.parse(init!.body as string);
      assert.equal(body.store, false);
      assert.equal(body.model, "test-model");
      assert.equal(body.text.format.strict, true);
      assert.equal(body.text.format.schema.additionalProperties, false);
      assert.deepEqual(JSON.parse(body.input[1].content[0].text), input);
      assert.match(body.input[0].content[0].text, /untrusted data/);
      return Response.json({ output: [{ type: "message", content: [{ type: "output_text", text: '{"items":[]}' }] }] });
    },
  });
  assert.deepEqual(await model.synthesize(input), { items: [] });
  assert.equal(model.promptVersion, "threadmind-insight-v2");
});

it("rejects provider errors, refusal, incomplete output, malformed JSON and oversized context without leaking payloads", async () => {
  for (const response of [
    new Response("secret-provider-body", { status: 429 }),
    Response.json({ output: [{ type: "message", content: [{ type: "refusal", refusal: "no" }] }] }),
    Response.json({ status: "incomplete", output: [] }),
    Response.json({ output: [{ type: "message", content: [{ type: "output_text", text: "not JSON" }] }] }),
  ]) {
    const model = new OpenAIResponsesInsightModel({ apiKey: "test-key", model: "test", fetch: async () => response });
    await assert.rejects(model.synthesize(input), { message: "insight_model_failed" });
  }
  let called = false;
  const model = new OpenAIResponsesInsightModel({ apiKey: "test-key", model: "test", fetch: async () => { called = true; throw new Error("network secret"); } });
  await assert.rejects(model.synthesize({ ...input, action: { ...input.action, fields: { huge: "x".repeat(200_001) } } }), /context_too_large/);
  assert.equal(called, false);
  await assert.rejects(model.synthesize(input), { message: "insight_model_failed" });
});

it("requires explicit model configuration and never silently falls back for missing credentials", () => {
  assert.ok(createInsightGenerator({}) instanceof EvidenceBackedInsightGenerator);
  assert.ok(createInsightGenerator({ THREADMIND_INSIGHT_MODEL: "chosen", OPENAI_API_KEY: "test" }) instanceof GroundedInsightGenerator);
  assert.throws(() => createInsightGenerator({ THREADMIND_INSIGHT_MODEL: "chosen" }), /API key/);
});

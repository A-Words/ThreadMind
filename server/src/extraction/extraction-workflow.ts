import { END, START, StateGraph, StateSchema } from "@langchain/langgraph";
import { z } from "zod/v4";
import { validateExtractionOutput, type ValidatedAnalysis } from "./extraction-output.ts";
import type { VisionExtractionModel } from "./vision-extraction-model.ts";

type ModelInput = Parameters<VisionExtractionModel["analyze"]>[0];

const WorkflowState = new StateSchema({
  input: z.custom<ModelInput>(),
  context: z.object({ accountId: z.string().min(1), submissionId: z.string().min(1) }),
  now: z.date(),
  rawOutput: z.unknown().optional(),
  analysis: z.custom<ValidatedAnalysis>().optional(),
});

export interface ExtractionWorkflow {
  analyze(
    input: ModelInput,
    context: { accountId: string; submissionId: string },
    now?: Date,
  ): Promise<ValidatedAnalysis>;
}

export class LangGraphExtractionWorkflow implements ExtractionWorkflow {
  private readonly graph: ReturnType<typeof createGraph>;

  constructor(model: VisionExtractionModel) {
    this.graph = createGraph(model);
  }

  async analyze(
    input: ModelInput,
    context: { accountId: string; submissionId: string },
    now = new Date(),
  ): Promise<ValidatedAnalysis> {
    const result = await this.graph.invoke({ input, context, now });
    if (!result.analysis) throw Object.assign(new Error("Extraction graph did not produce validated analysis"), { code: "invalid_model_output" });
    return result.analysis;
  }
}

function createGraph(model: VisionExtractionModel) {
  return new StateGraph(WorkflowState)
    .addNode("multimodal_analysis", async (state) => ({ rawOutput: await model.analyze(state.input) }))
    .addNode("domain_validation", (state) => ({
      analysis: validateExtractionOutput(state.rawOutput, state.context, state.now),
    }))
    .addEdge(START, "multimodal_analysis")
    .addEdge("multimodal_analysis", "domain_validation")
    .addEdge("domain_validation", END)
    .compile();
}

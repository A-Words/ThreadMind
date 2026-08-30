export interface VisionExtractionModel {
  analyze(input: {
    image: Uint8Array;
    contentType: "image/png" | "image/jpeg" | "image/webp";
    supplementalText?: string;
  }): Promise<unknown>;
}

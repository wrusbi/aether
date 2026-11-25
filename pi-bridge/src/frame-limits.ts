/**
 * Frame limits and memory safety module for Aether Pi Bridge.
 * Protects against OOM during parsing of large JSON payloads and IPC frames.
 */

export const FRAME_LIMITS = {
  MAX_FRAME_SIZE_BYTES: 5 * 1024 * 1024, // 5 MB max input frame size
  MAX_BUFFER_ACCUMULATION_BYTES: 10 * 1024 * 1024, // 10 MB limit before force truncation
  MAX_STRING_LENGTH: 1 * 1024 * 1024, // 1 MB per string slice
  TRUNCATION_MARKER: "\n...[truncated by frame-limits]...\n",
} as const;

export interface FrameParseResult<T> {
  success: boolean;
  data?: T;
  error?: string;
  bytesProcessed: number;
  truncated: boolean;
}

export function safeParseJsonFrame<T = unknown>(rawInput: string): FrameParseResult<T> {
  const bytesProcessed = Buffer.byteLength(rawInput, "utf8");

  if (bytesProcessed > FRAME_LIMITS.MAX_FRAME_SIZE_BYTES) {
    return {
      success: false,
      error: `Frame size (${bytesProcessed} bytes) exceeds limit of ${FRAME_LIMITS.MAX_FRAME_SIZE_BYTES} bytes.`,
      bytesProcessed,
      truncated: true,
    };
  }

  try {
    const parsed = JSON.parse(rawInput) as T;
    return {
      success: true,
      data: parsed,
      bytesProcessed,
      truncated: false,
    };
  } catch (err: any) {
    return {
      success: false,
      error: err?.message || "Failed to parse JSON frame",
      bytesProcessed,
      truncated: false,
    };
  }
}

export function truncatePayloadString(text: string, maxLength: number = FRAME_LIMITS.MAX_STRING_LENGTH): string {
  if (text.length <= maxLength) {
    return text;
  }
  const half = Math.floor((maxLength - FRAME_LIMITS.TRUNCATION_MARKER.length) / 2);
  return text.substring(0, half) + FRAME_LIMITS.TRUNCATION_MARKER + text.substring(text.length - half);
}

export class SafeFrameAccumulator {
  private buffer = "";
  private readonly maxBytes: number;

  constructor(maxBytes: number = FRAME_LIMITS.MAX_BUFFER_ACCUMULATION_BYTES) {
    this.maxBytes = maxBytes;
  }

  public append(chunk: string): boolean {
    this.buffer += chunk;
    if (Buffer.byteLength(this.buffer, "utf8") > this.maxBytes) {
      this.buffer = truncatePayloadString(this.buffer, Math.floor(this.maxBytes / 2));
      return true;
    }
    return false;
  }

  public consumeLines(): string[] {
    const lines = this.buffer.split("\n");
    this.buffer = lines.pop() || "";
    return lines;
  }

  public clear(): void {
    this.buffer = "";
  }

  public get currentSize(): number {
    return Buffer.byteLength(this.buffer, "utf8");
  }
}
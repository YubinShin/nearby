import type { AskResponse, HybridResponse, SearchResponse } from "@/shared/types";

const SEARCH_BASE = import.meta.env.VITE_SEARCH_BASE ?? "/api/search";
const ASK_BASE = import.meta.env.VITE_ASK_BASE ?? "/api/ask";

export interface Timed<T> {
  body: T;
  rtt: number;
}

export class ApiError extends Error {
  readonly status: number;
  readonly upstream: string | null;

  constructor(message: string, status: number, upstream: string | null = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.upstream = upstream;
  }
}

async function toError(res: Response): Promise<ApiError> {
  const text = await res.text().catch(() => "");
  try {
    const body = JSON.parse(text) as { message?: string; error?: string; upstream?: string };
    return new ApiError(
      body.message || body.error || res.statusText,
      res.status,
      body.upstream ?? null,
    );
  } catch {
    return new ApiError(text.slice(0, 200) || res.statusText, res.status);
  }
}

async function get<T>(base: string, path: string, params: URLSearchParams): Promise<Timed<T>> {
  const started = performance.now();
  let res: Response;
  try {
    res = await fetch(`${base}${path}?${params}`);
  } catch {
    throw new ApiError(`${base}${path} 에 연결하지 못했습니다`, 0);
  }
  const rtt = Math.round(performance.now() - started);
  if (!res.ok) {
    throw await toError(res);
  }
  return { body: (await res.json()) as T, rtt };
}

export const searchApi = {
  keyword: (params: URLSearchParams) => get<SearchResponse>(SEARCH_BASE, "/v1/search", params),
  vector: (params: URLSearchParams) => get<SearchResponse>(SEARCH_BASE, "/v1/vsearch", params),
  hybrid: (params: URLSearchParams) => get<HybridResponse>(SEARCH_BASE, "/v1/hsearch", params),
};

export const askApi = {
  ask: (params: URLSearchParams) => get<AskResponse>(ASK_BASE, "/v1/ask", params),
};
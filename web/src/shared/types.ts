export interface PlaceHit {
  placeId: string;
  name: string;
  branch: string | null;
  brand: string | null;
  label: string;
  category: string | null;
  address: string | null;
  sigungu: string | null;
  dong: string | null;
  lat: number | null;
  lon: number | null;
  score: number;
  distanceM: number | null;
  highlight: string[];
  ranks?: Record<string, number>;
  scores?: Record<string, number>;
}

export interface SearchResponse {
  query: string;
  total: number;
  page: number;
  size: number;
  tookMs: number;
  relaxed?: boolean;
  hits: PlaceHit[];
}

export interface ChannelReport {
  name: string;
  candidates: number;
  tookMs: number;
  failed: boolean;
}

export interface HybridResponse extends SearchResponse {
  degraded?: boolean;
  channels?: ChannelReport[];
}

export interface ParsedQuery {
  keyword: string;
  categoryHint: string | null;
  geoAnchor: string | null;
  radiusM: number | null;
  expectsEmpty: boolean;
}

export interface SearchRequestPlan {
  q: string;
  size: number;
  lat: number | null;
  lon: number | null;
  radius: number | null;
  unmapped: string[];
  unsupported: string[];
}

export interface AskResponse {
  query: string;
  parsed: ParsedQuery | null;
  applied: SearchRequestPlan;
  degraded: boolean;
  degradedBy: string[];
  llmVendor: string;
  llmTookMs: number;
  searchTookMs: number;
  tookMs: number;
  search: HybridResponse;
}
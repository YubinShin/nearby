export function num(value: number | null | undefined, digits = 3): string {
  return value == null ? "–" : value.toFixed(digits);
}

export function scoreOf(channel: string, value: number | null | undefined): string {
  return num(value, channel === "keyword" ? 1 : 3);
}

export function ms(value: number): string {
  return `${value}ms`;
}

export const GEO_PRESETS = [
  { value: "", label: "안 씀" },
  { value: "37.4979,127.0276", label: "강남역" },
  { value: "37.5045,127.0489", label: "선릉역" },
  { value: "37.5273,127.0286", label: "압구정역" },
];

export function applyGeo(params: URLSearchParams, geo: string, radius: string): void {
  if (!geo) return;
  const [lat, lon] = geo.split(",");
  params.set("lat", lat);
  params.set("lon", lon);
  if (radius) params.set("radius", radius);
}
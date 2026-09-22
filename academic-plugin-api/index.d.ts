/** Native Plugin API v3, with compatible academic v1/v2 interfaces. */
export type ErrorCode = 'UNSUPPORTED' | 'NOT_OPEN' | 'INVALID_CREDENTIALS' | 'SESSION_EXPIRED' |
  'CAPTCHA_REQUIRED' | 'WEB_LOGIN_REQUIRED' | 'NO_CAPACITY' | 'CONFLICT' | 'ALREADY_SELECTED' |
  'CREDIT_LIMIT' | 'PAGE_CHANGED' | 'NETWORK_RETRYABLE' | 'RESULT_UNKNOWN' | 'UNTRUSTED_URL' |
  'VALIDATION_FAILED' | 'TIMEOUT' | 'CANCELLED' | 'RUNTIME_EXITED' | 'RESOURCE_LIMIT' | 'BAD_SIGNATURE' | 'PERMISSION_DENIED' | 'STALE_CONTEXT';
export interface PluginError { code: ErrorCode; message: string; details?: Record<string, string>; }
export type Result<T> = { ok: true; data: T } | { ok: false; error: PluginError };
export interface Context {
  schoolId: string; accountId: string; sessionEpoch: number;
  providerId: string; providerVersion: string; operationId: string;
  baseUrl: string; development: boolean;
  pageId?: string; pageInstance?: string; capabilities?: HostCapability[];
}
export interface Term {
  id: string; name: string; year?: number; semester?: number; order?: number;
  startDate?: string; endDate?: string; nextId?: string;
}
export interface Terms { items: Term[]; currentId: string; }
export interface Page<T> { items: T[]; nextCursor?: string; }
export interface PageQuery { cursor?: string; pageSize?: number; }
export interface AuthStart { username: string; password: string; }
export interface AuthResume { continuationId: string; captcha?: string; webLoginCompleted?: boolean; }
export type AuthState =
  { status: 'authenticated'; studentId: string; studentName: string } |
  { status: 'captcha'; continuationId: string; imageBase64: string; mimeType: string } |
  { status: 'webLogin'; continuationId: string; url: string; completionUrl: string };
export interface ScheduleEntry {
  id: string; name: string; teacher?: string; location?: string;
  day: number; startPeriod: number; endPeriod: number; weeks: number[];
}
export interface Schedule { termId: string; entries: ScheduleEntry[]; maxWeeks: number; }
export interface Calendar {
  termId: string; startDate?: string;
  periods: { number: number; start: string; end: string }[];
}
export interface Grade {
  id: string; name: string; score: string; credits?: string; gradePoint?: string;
  type?: string; termId?: string; code?: string; college?: string; sectionId?: string;
}
export interface Grades extends Page<Grade> { gradePointAverage?: string; totalCredits?: string; }
export interface GradeDetails { gradeId: string; items: { name: string; score: string; weight?: string }[]; }
export interface Exam { id: string; name: string; time: string; location?: string; seat?: string; examName?: string; teacher?: string; }
export interface Round { id: string; name: string; termId?: string; open: boolean; }
export interface Course {
  id: string; name: string; roundId: string; teacher?: string; time?: string; location?: string;
  credits?: string; capacity?: number; selected?: number; sectionId?: string; sectionName?: string;
}
export interface Section { id: string; courseId: string; name?: string; teacher?: string; time?: string; location?: string; capacity?: number; selected?: number; }
export interface Enrollment { id: string; courseId: string; sectionId: string; name: string; teacher?: string; time?: string; location?: string; credits?: string; }
export interface SelectionTarget { courseId: string; sectionId: string; roundId: string; }
export interface SelectionReceipt { confirmed: boolean; enrollment?: Enrollment; message?: string; }
export interface HttpRequest {
  url: string; method?: 'GET' | 'POST'; purpose: 'query' | 'auth' | 'mutation';
  headers?: Record<string, string>; form?: Record<string, string>; body?: string;
  charset?: 'UTF-8' | 'GBK' | 'GB2312' | 'GB18030'; responseType?: 'text' | 'base64';
}
export interface HttpResponse { status: number; url: string; headers: Record<string, string>; body: string; }
export interface HtmlNode { text: string; html: string; attributes: Record<string, string>; }
export interface HostSdk {
  capabilities: {list(): Promise<HostCapability[]>};
  http(request: HttpRequest): Promise<HttpResponse>;
  html: {
    select(html: string, selector: string): HtmlNode[];
    text(html: string): string;
    form(html: string, selector?: string): Record<string, string>;
    table(html: string, selector?: string): string[][];
  };
  crypto: {
    digest(algorithm: 'SHA-256' | 'MD5', text: string): Promise<string>;
    hmacSha256(key: string, text: string): Promise<string>;
    aesCbcEncrypt(keyBase64: string, ivBase64: string, text: string): Promise<string>;
    rsaEncrypt(publicKeySpkiBase64: string, text: string): Promise<string>;
    base64(text: string): Promise<string>;
  };
  /** Session state may retain protocol tokens; it is discarded on session invalidation. */
  state: { get<T = unknown>(key: string): Promise<T | null>; set(key: string, value: unknown): Promise<void>; remove(key: string): Promise<void> };
  /** Persistent storage is scoped by school/account/provider. Never store credentials here. */
  storage: { get<T = unknown>(key: string): Promise<T | null>; set(key: string, value: unknown): Promise<void>; remove(key: string): Promise<void> };
  log(level: 'debug' | 'info' | 'warn' | 'error', message: string, fields?: Record<string, unknown>): Promise<void>;
}
export type Handler<A, R> = (args: A, context: Readonly<Context>, sdk: HostSdk) => Promise<Result<R>> | Result<R>;
export interface Auth {
  start: Handler<AuthStart, AuthState>;
  resume: Handler<AuthResume, AuthState>;
  refreshCaptcha: Handler<{ continuationId: string }, AuthState>;
  validate: Handler<Record<string, never>, AuthState>;
}
export interface Study {
  terms: Handler<Record<string, never>, Terms>;
  schedule: Handler<{ termId: string }, Schedule>;
  calendar: Handler<{ termId: string }, Calendar>;
  grades: Handler<PageQuery & { termId?: string }, Grades>;
  gradeDetails: Handler<{ gradeId: string }, GradeDetails>;
  exams: Handler<PageQuery & { termId: string }, Page<Exam>>;
}
export interface Selection {
  catalog: Handler<PageQuery, Page<Round>>;
  courses: Handler<PageQuery & { roundId: string; keyword?: string; teacher?: string }, Page<Course>>;
  sections: Handler<PageQuery & { courseId: string; roundId: string }, Page<Section>>;
  enrolled: Handler<PageQuery & { roundId?: string }, Page<Enrollment>>;
  select: Handler<SelectionTarget, SelectionReceipt>;
  drop: Handler<SelectionTarget & { enrollmentId: string }, SelectionReceipt>;
}
/** Authentication and selection are replaced as complete groups. Study methods are independent. */
export interface AcademicPlugin { auth?: Auth; study?: Partial<Study>; selection?: Selection; }
export type Capability = `auth.${keyof Auth}` | `study.${keyof Study}` | `selection.${keyof Selection}` | `service.${keyof Service}` | 'ui.init' | 'ui.reduce' | 'task.run' | 'data.query';
export interface NetworkRule {
  origin: string; pathPrefix: string; methods: ('GET' | 'POST')[]; purposes: ('query' | 'auth' | 'mutation')[];
  requiredQuery?: Record<string, string>; requiredForm?: Record<string, string>;
}
export interface PluginManifest {
  id: string; name: string; version: string; apiVersion: 1 | 2 | 3;
  kind: 'configuration' | 'extension' | 'independent' | 'service' | 'native';
  extends?: 'builtin.zf' | 'builtin.zf_old' | 'builtin.qz' | 'builtin.qz_old' | 'builtin.legacy_zf';
  entry?: 'index.js'; capabilities: Capability[]; network: NetworkRule[];
  school?: { id: string; name: string; domain: string; protocol: 'http' | 'https'; basePath: string; academicSystem?: string; allowedAcademicHosts?: string[]; pageCharset?: string };
  service?: ServiceManifest;
  description?: string; files: Record<string, string>;
  permissions?: HostPermission[]; requires?: {name: string; version: number}[];
  matches?: SchoolMatch[]; contributes?: Contributions;
  authorRef?: string; features?: string[]; releaseNotes?: string;
}

/** API v2: separate service sessions and native, declarative pages. API v1 remains supported. */
export interface ServiceManifest {
  schoolIds: string[];
  /** Exact academic host aliases, for custom school configurations with a different ID. */
  academicHosts?: string[];
  authentication: { mode: 'none' | 'password'; usernameLabel?: string; passwordLabel?: string; help?: string };
  pages: { id: string; title: string }[];
  entries: { id: string; title: string; pageId: string; icon: 'school' | 'book' | 'chart' | 'calendar' | 'wallet' | 'activity'; order: number }[];
  actions: { id: string; title: string; kind: 'query' | 'mutation'; confirmation?: string }[];
}
export type ServiceParams = Record<string, string>;
export type ServiceLink = { type: 'page'; pageId: string; params?: ServiceParams } |
  { type: 'action'; actionId: string; params?: ServiceParams } | { type: 'url'; url: string };
export type ServiceTone = 'default' | 'positive' | 'warning';
export interface ServiceField {
  id: string; label: string; type: 'text' | 'number' | 'select'; required: boolean;
  placeholder?: string; value?: string; options?: { value: string; label: string }[];
}
export type ServiceBlock =
  { id: string; type: 'profile'; title: string; subtitle?: string; details?: string[]; badge?: string } |
  { id: string; type: 'metrics'; title?: string; columns: 1 | 2 | 3; items: { id: string; label: string; value: string; unit?: string }[] } |
  { id: string; type: 'progress'; title: string; items: { id: string; label: string; value: number; max: number; detail?: string; displayValue?: string; tone?: ServiceTone }[] } |
  { id: string; type: 'list'; title?: string; items: { id: string; title: string; subtitle?: string; value?: string; action?: ServiceLink }[] } |
  { id: string; type: 'notice'; text: string; tone?: ServiceTone } |
  { id: string; type: 'actions'; title?: string; items: { label: string; action: ServiceLink }[] } |
  { id: string; type: 'form'; title?: string; fields: ServiceField[]; submit: { label: string; actionId: string } };
export interface ServicePage { pageId: string; title: string; subtitle?: string; layout?: 'comfortable' | 'compact'; blocks: ServiceBlock[] }
export interface ServiceReceipt { actionId: string; confirmed: boolean; message?: string; page?: ServicePage }
export interface Service {
  page: Handler<{ pageId: string; params?: ServiceParams }, ServicePage>;
  /** Only declared mutation actions can send purpose=mutation, after the host's confirmation. */
  action: Handler<{ actionId: string; params?: ServiceParams }, ServiceReceipt>;
}
export interface CampusServicePlugin { auth?: Auth; service: Pick<Service, 'page'> & Partial<Pick<Service, 'action'>> }

export type Json = null | boolean | number | string | Json[] | {[key: string]: Json};
export type HostPermission = 'network' | 'storage' | 'credentials' | 'session' | 'files' | 'device.clipboard' | 'device.haptics' | 'tasks' | 'notifications' | 'navigation' | 'auth' | 'runtime';
export interface SchoolMatch {host: string; port?: number; protocol?: 'http' | 'https'; pathPrefix: string}
export interface Contributions {
  pages: {id: string; title: string}[];
  entries: {id: string; title: string; pageId: string; icon?: 'school' | 'book' | 'chart' | 'calendar' | 'wallet' | 'activity'; order?: number}[];
  menuActions?: {id: string; title: string; event: string; pageId?: string}[];
  dataProviders?: {id: string; title: string}[];
  tasks?: {id: string; title: string}[];
  /** Optional academic extension; requires school and academic method declarations. */
  academic?: boolean;
}
export interface UiBase {id: string; enabled?: boolean; padding?: number; weight?: number; height?: number; surface?: 'none' | 'glass' | 'tonal'; tone?: 'default' | 'primary' | 'positive' | 'warning' | 'error'}
export type UiNode = UiBase & (
  {type: 'column' | 'row' | 'box' | 'scroll' | 'list'; children: UiNode[]; gap?: number; align?: 'start' | 'center' | 'end'; onEnd?: string} |
  {type: 'text'; text: string; style?: 'title' | 'body' | 'label' | 'caption'; maxLines?: number} |
  {type: 'image'; handle: string; label: string} |
  {type: 'button'; label: string; event: string} |
  {type: 'input'; label: string; value: string; event: string; inputType?: 'text' | 'number' | 'password' | 'multiline'; placeholder?: string} |
  {type: 'toggle'; label: string; value: boolean; event: string} |
  {type: 'select'; label: string; value: string; event: string; options: {value: string; label: string}[]} |
  {type: 'slider'; label: string; value: number; min: number; max: number; steps?: number; event: string} |
  {type: 'progress'; value: number; label?: string} |
  {type: 'divider' | 'spacer'} |
  {type: 'canvas'; width: number; height: number; label: string; shapes: DrawShape[]}
);
export interface DrawShape {id: string; type: 'line' | 'rect' | 'circle' | 'path'; x?: number; y?: number; x2?: number; y2?: number; width?: number; height?: number; radius?: number; stroke?: number; color?: 'primary' | 'foreground' | 'muted' | 'positive' | 'warning' | 'error'; points?: {x: number; y: number}[]}
export interface HostCapability {name: string; version: number; permission: HostPermission; input: Record<string, Json>; output: Record<string, Json>; cancellable: boolean; userGesture: boolean; limits: Record<string, number>}
export interface UiEffect {id: string; capability: string; version: number; input: Record<string, Json>; timeoutMs?: number}
export interface UiEvent {type: 'click' | 'input' | 'navigate' | 'list.end' | 'effect.result' | 'lifecycle'; nodeId?: string; name?: string; value?: Json; effectId?: string; result?: Result<Json>}
export interface UiResult<S extends Json = Json> {state: S; view: UiNode; effects: UiEffect[]}
export interface TaskResult<S extends Json = Json> {state: S; effects: UiEffect[]}
export type UiHandler<A, R> = (args: A, context: Readonly<Context>, sdk: Pick<HostSdk, 'capabilities' | 'html' | 'crypto' | 'log'>) => R | Promise<R>;
export interface NativePlugin<S extends Json = Json> {
  ui: {
    init: UiHandler<{pageId: string; params: Record<string, Json>}, UiResult<S>>;
    reduce: UiHandler<{pageId: string; state: S; event: UiEvent}, UiResult<S>>;
  };
  task?: {run: UiHandler<{taskId: string; input: Json; state: S; event?: UiEvent}, TaskResult<S>>};
  data?: {query: UiHandler<{providerId: string; input: Json; cursor?: string}, {items: Json[]; nextCursor?: string}>};
}

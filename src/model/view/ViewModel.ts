import { ContentfulStatusCode } from "hono/utils/http-status";

export interface ViewModel {
    headers?: Record<string, string>;
    isSJIS?: boolean;
    status?: ContentfulStatusCode;
    [key: string]: any;
}

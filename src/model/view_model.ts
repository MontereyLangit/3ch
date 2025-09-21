export interface ViewModel {
    headers?: Record<string, string>;
    isSJIS?: boolean;
    status?: number;
    [key: string]: any;
}

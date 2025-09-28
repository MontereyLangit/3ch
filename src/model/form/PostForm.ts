import { z } from 'zod';

/**
 * 書き込みフォームスキーマ
 * FROM、mail、MESSAGE、subjectの最大長は板設定/スレ設定により指定可能
 */
export const PostFormSchema = z.object({
    FROM: z.string().max(100).default(""),
    mail: z.string().max(100).default(""),
    MESSAGE: z.string().min(1).max(10000),
    subject: z.string().max(100).default(""),
    bbs: z.string().min(1).max(64),
    key: z.string().length(10).regex(/^\d+$/),
    time: z.string().length(10).regex(/^\d+$/),
    submit: z.string().min(1).max(100),
});

export type PostForm = z.infer<typeof PostFormSchema>;

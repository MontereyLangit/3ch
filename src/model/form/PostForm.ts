import { z } from 'zod';

/**
 * 書き込みフォームのスキーマを生成します。
 * @param settings 板設定/スレ設定
 * @returns Zodスキーマ
 */
export const createPostFormSchema = (settings: Record<string, any>) => {
    const nameLengthLimit = parseInt(settings.BBS_NAME_COUNT || "64");
    const mailLengthLimit = parseInt(settings.BBS_MAIL_COUNT || "64");
    const messageLengthLimit = parseInt(settings.BBS_MESSAGE_COUNT || "4096");
    const subjectLengthLimit = parseInt(settings.BBS_SUBJECT_COUNT || "64");
    const defaultName = settings.BBS_NONAME_NAME || "名無しさん＠３ちゃんねる";

    return z.object({
        FROM: z.string().max(nameLengthLimit, { message: `名前は${nameLengthLimit}文字以内で入力してください。` }).default(defaultName),
        mail: z.string().max(mailLengthLimit, { message: `メールアドレスは${mailLengthLimit}文字以内で入力してください。` }).default(""),
        MESSAGE: z.string()
            .min(1, { message: "本文を入力してください。" })
            .max(messageLengthLimit, { message: `本文は${messageLengthLimit}文字以内で入力してください。` }),
        subject: z.string().max(subjectLengthLimit, { message: `件名は${subjectLengthLimit}文字以内で入力してください。` }).default(""),
        bbs: z.string().min(1, { message: "板名が指定されていません。" }).max(64),
        key: z.string().length(10, { message: "キーが不正です。" }).regex(/^\d+$/, { message: "キーは数字である必要があります。" }),
        time: z.string().length(10, { message: "timeが不正です。" }).regex(/^\d+$/, { message: "timeは数字である必要があります。" }),
        submit: z.string().min(1).max(100),
    });
}

export type PostForm = z.infer<ReturnType<typeof createPostFormSchema>>;

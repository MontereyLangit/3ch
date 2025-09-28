/**
 * キャップユーザー
 */
export class CapUser {
    id: string;
    name: string;
    isSuperCap: boolean;

    constructor(id: string, name: string, isSuperCap: boolean) {
        this.id = id;
        this.name = name;
        this.isSuperCap = isSuperCap;
    }
}

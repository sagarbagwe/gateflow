/** One instance per form/command. Never rotate an unresolved intent on retry. */
export class CommandIntent {
  private fingerprint = "";
  private key = "";
  private pending: Promise<unknown> | null = null;
  async run<T>(
    path: string,
    body: unknown,
    send: (key: string) => Promise<T>,
  ): Promise<T> {
    const fingerprint = JSON.stringify([path, body]);
    if (this.pending) {
      if (fingerprint !== this.fingerprint)
        throw new Error("A command is already in progress.");
      return this.pending as Promise<T>;
    }
    if (fingerprint !== this.fingerprint) {
      this.fingerprint = fingerprint;
      this.key = crypto.randomUUID();
    }
    this.pending = send(this.key);
    try {
      const result = (await this.pending) as T;
      this.fingerprint = "";
      this.key = "";
      return result;
    } finally {
      // Failure preserves both the original key and fingerprint.
      this.pending = null;
    }
  }
}

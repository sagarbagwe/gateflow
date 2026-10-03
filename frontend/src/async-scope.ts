/** Suppresses old responses after account/workspace changes or a newer reload. */
export class AsyncScope {
  private epoch = 0;
  private channels = new Map<string, number>();
  invalidate() {
    this.epoch++;
    this.channels.clear();
  }
  start(channel: string): () => boolean {
    const epoch = this.epoch;
    const sequence = (this.channels.get(channel) || 0) + 1;
    this.channels.set(channel, sequence);
    return () =>
      epoch === this.epoch && this.channels.get(channel) === sequence;
  }
}

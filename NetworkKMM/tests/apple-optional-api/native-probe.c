#include "networkkmm_curl_facts_compat.h"
#include <stdio.h>
int main(void) {
  CurClientHandle h = CreateCurlClient("Jony-task172-link-only");
  if (!h) return 2;
  CurlTransferInfoV1 transfer = {0}; CurlCompletionInfoV1 completion = {0};
  int multi = NetworkKmmCurlMultiApiAvailable();
  int facts = NetworkKmmGetCurlTransferInfoV1IfAvailable(h, &transfer, sizeof(transfer), CURL_TRANSFER_INFO_ABI_VERSION);
  int complete = NetworkKmmGetCurlCompletionInfoV1IfAvailable(h, &completion, sizeof(completion), CURL_COMPLETION_INFO_ABI_VERSION);
  int limit = NetworkKmmSetCurlMaxBufferedResponseBytesIfAvailable(h, 1024);
  int idle = NetworkKmmSetCurlBufferedBodyIdleTimeoutMsIfAvailable(h, 1000);
  printf("abi=%d multi=%d transfer=%d completion=%d bodyLimit=%d bodyIdle=%d\n", CurlWrapperAbiVersion(), multi, facts, complete, limit, idle);
  DeleteCurlClient(h);
  return multi && facts && complete && limit && idle ? 0 : 3;
}

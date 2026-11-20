#include "hw/xbox/hle/shader/retroarch_preset.h"
#include <assert.h>
#include <string.h>
int main(void){
 const char *valid="shaders = 2\nshader0 = \"pass0.glsl\"\nshader1 = pass1.glsl\nfilter_linear0 = true\nframe_count1 = 3\nscale_type1 = absolute\nscale1 = 2.0\n";
 HleRaPreset p;char error[128];assert(hle_ra_preset_parse(valid,strlen(valid),&p,error,sizeof(error)));
 assert(p.pass_count==2&&!strcmp(p.passes[0].shader,"pass0.glsl"));assert(p.passes[0].filter_linear&&p.passes[0].frame_count==1);
 assert(p.passes[1].frame_count==3&&p.passes[1].scale_type==HLE_RA_SCALE_ABSOLUTE);assert(p.passes[1].scale_x==2.0f&&p.passes[1].scale_y==1.5f);
 const char *bad_filter="shaders=1\\nshader0=a.glsl\\nfilter_linear0=maybe\\n";assert(!hle_ra_preset_parse(bad_filter,strlen(bad_filter),&p,error,sizeof(error)));\n const char *traversal="shaders=1\nshader0=../escape.glsl\n";assert(!hle_ra_preset_parse(traversal,strlen(traversal),&p,error,sizeof(error)));
 const char *missing="shaders=2\nshader0=one.glsl\n";assert(!hle_ra_preset_parse(missing,strlen(missing),&p,error,sizeof(error)));
 const char *bad_count="shaders=99\nshader0=a.glsl\n";assert(!hle_ra_preset_parse(bad_count,strlen(bad_count),&p,error,sizeof(error)));
 char many[8192] = {0};
 size_t used = (size_t)snprintf(many, sizeof(many), "shaders=48\n");
 for (unsigned i = 0; i < 48; ++i) {
  int n = snprintf(many + used, sizeof(many) - used,
                   "shader%u=pass%u.slang\n", i, i);
  assert(n > 0 && (size_t)n < sizeof(many) - used);
  used += (size_t)n;
 }
 assert(hle_ra_preset_parse(many, used, &p, error, sizeof(error)));
 assert(p.pass_count == 48);
 assert(!strcmp(p.passes[47].shader, "pass47.slang"));
 const char *too_many = "shaders=65\nshader0=pass0.slang\n";
 assert(!hle_ra_preset_parse(too_many, strlen(too_many), &p, error, sizeof(error)));
 return 0;
}

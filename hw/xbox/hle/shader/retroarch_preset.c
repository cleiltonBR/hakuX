#include "hw/xbox/hle/shader/retroarch_preset.h"
#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define RA_TEXT_MAX (256u * 1024u)
#define RA_LINE_MAX 4096u
#define RA_FRAMES_MAX 32u
static void fail(char *dst,size_t n,const char *msg){if(dst&&n)snprintf(dst,n,"%s",msg);}
static char *trim(char *s){while(isspace((unsigned char)*s))++s;char *e=s+strlen(s);while(e>s&&isspace((unsigned char)e[-1]))--e;*e=0;return s;}
static bool safe_path(const char *s){
 if(!s||!*s||s[0]=='/'||s[0]=='\\'||(isalpha((unsigned char)s[0])&&s[1]==':'))return false;
 const char *p=s;while(*p){const char *q=p;while(*q&&*q!='/'&&*q!='\\')++q;size_t n=(size_t)(q-p);
 if(!n||(n==2&&p[0]=='.'&&p[1]=='.'))return false;p=*q?q+1:q;}return true;
}
static bool number(char *s,float *v){char *end=NULL;errno=0;float x=strtof(s,&end);if(errno||end==s||*trim(end)||!(x>0.0f)||x>16384.0f)return false;*v=x;return true;}
bool hle_ra_preset_parse(const char *text,size_t length,HleRaPreset *out,char *error,size_t error_size){
 if(!text||!out||!length||length>RA_TEXT_MAX){fail(error,error_size,"preset size is invalid");return false;}
 char *copy=malloc(length+1);if(!copy){fail(error,error_size,"out of memory");return false;}
 memcpy(copy,text,length);copy[length]=0;HleRaPreset parsed;memset(&parsed,0,sizeof(parsed));
 char *values[HLE_RA_MAX_PASSES]={0},*save=NULL;unsigned declared=0;bool has_count=false;
 for(char *line=strtok_r(copy,"\n",&save);line;line=strtok_r(NULL,"\n",&save)){
  if(strlen(line)>RA_LINE_MAX){fail(error,error_size,"preset line too long");goto bad;}
  char *p=trim(line);if(!*p||*p=='#'||*p==';')continue;char *eq=strchr(p,'=');if(!eq)continue;*eq++=0;
  char *key=trim(p),*value=trim(eq);size_t n=strlen(value);if(n>=2&&value[0]=='"'&&value[n-1]=='"'){value[n-1]=0;++value;}
  if(!strcmp(key,"shaders")){char *end=NULL;unsigned long v=strtoul(value,&end,10);if(end==value||*trim(end)||v<1||v>HLE_RA_MAX_PASSES){fail(error,error_size,"shader pass count outside 1..32");goto bad;}declared=(unsigned)v;has_count=true;continue;}
  if(!strncmp(key,"shader",6)&&isdigit((unsigned char)key[6])){
   char *end=NULL;unsigned long ix=strtoul(key+6,&end,10);
   if(*end||ix>=HLE_RA_MAX_PASSES||!safe_path(value)||strlen(value)>=HLE_RA_PATH_MAX){fail(error,error_size,"invalid shader path or pass index");goto bad;}
   free(values[ix]);values[ix]=malloc(strlen(value)+1);if(!values[ix]){fail(error,error_size,"out of memory");goto bad;}strcpy(values[ix],value);
  }
 }
 if(!has_count)for(unsigned i=0;i<HLE_RA_MAX_PASSES;i++)if(values[i])declared=i+1;
 if(!declared){fail(error,error_size,"preset declares no shader passes");goto bad;}parsed.pass_count=declared;
 memset(copy,0,length+1);memcpy(copy,text,length);save=NULL;
 for(char *line=strtok_r(copy,"\n",&save);line;line=strtok_r(NULL,"\n",&save)){
  char *p=trim(line);if(!*p||*p=='#'||*p==';')continue;char *eq=strchr(p,'=');if(!eq)continue;*eq++=0;
  char *key=trim(p),*value=trim(eq);size_t n=strlen(value);if(n>=2&&value[0]=='"'&&value[n-1]=='"'){value[n-1]=0;++value;}
  char *end=NULL;unsigned long ix;
  if(!strncmp(key,"filter_linear",13)&&isdigit((unsigned char)key[13])){ix=strtoul(key+13,&end,10);if(*end||ix>=declared)continue;if(!strcmp(value,"true")||!strcmp(value,"1"))parsed.passes[ix].filter_linear=true;else if(!strcmp(value,"false")||!strcmp(value,"0"))parsed.passes[ix].filter_linear=false;else{fail(error,error_size,"invalid filter_linear value");goto bad;}}
  else if(!strncmp(key,"frame_count",11)&&isdigit((unsigned char)key[11])){ix=strtoul(key+11,&end,10);if(*end||ix>=declared)continue;unsigned long v=strtoul(value,&end,10);if(end==value||*trim(end)||v<1||v>RA_FRAMES_MAX){fail(error,error_size,"frame_count outside 1..32");goto bad;}parsed.passes[ix].frame_count=(unsigned)v;}
  else if(!strncmp(key,"scale_type",10)&&isdigit((unsigned char)key[10])){ix=strtoul(key+10,&end,10);if(*end||ix>=declared)continue;if(!strcmp(value,"source"))parsed.passes[ix].scale_type=HLE_RA_SCALE_SOURCE;else if(!strcmp(value,"absolute"))parsed.passes[ix].scale_type=HLE_RA_SCALE_ABSOLUTE;else if(!strcmp(value,"viewport"))parsed.passes[ix].scale_type=HLE_RA_SCALE_VIEWPORT;else{fail(error,error_size,"unsupported scale_type");goto bad;}}
  else if(!strncmp(key,"scale",5)&&isdigit((unsigned char)key[5])){
   ix=strtoul(key+5,&end,10);if(ix>=declared)continue;
   if(!strcmp(end,"_x")){if(!number(value,&parsed.passes[ix].scale_x)){fail(error,error_size,"invalid horizontal scale");goto bad;}}
   else if(!strcmp(end,"_y")){if(!number(value,&parsed.passes[ix].scale_y)){fail(error,error_size,"invalid vertical scale");goto bad;}}
   else if(!*end){float v;if(!number(value,&v)){fail(error,error_size,"invalid scale value");goto bad;}parsed.passes[ix].scale_x=v;parsed.passes[ix].scale_y=v;}
  }
 }
 for(unsigned i=0;i<declared;i++){if(!values[i]){fail(error,error_size,"preset has a missing shader pass");goto bad;}strcpy(parsed.passes[i].shader,values[i]);if(!parsed.passes[i].frame_count)parsed.passes[i].frame_count=1;if(parsed.passes[i].scale_type==HLE_RA_SCALE_ABSOLUTE&&(!parsed.passes[i].scale_x||!parsed.passes[i].scale_y)){fail(error,error_size,"absolute scale requires scaleN or both scaleN_x and scaleN_y");goto bad;}if(!parsed.passes[i].scale_x)parsed.passes[i].scale_x=parsed.passes[i].scale_y;if(!parsed.passes[i].scale_y)parsed.passes[i].scale_y=parsed.passes[i].scale_x;}
 for(unsigned i=0;i<HLE_RA_MAX_PASSES;i++)free(values[i]);free(copy);*out=parsed;if(error&&error_size)error[0]=0;return true;
bad:for(unsigned i=0;i<HLE_RA_MAX_PASSES;i++)free(values[i]);free(copy);return false;
}

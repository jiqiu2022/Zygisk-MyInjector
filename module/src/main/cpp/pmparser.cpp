/*
 @Author	: ouadimjamal@gmail.com
 @date		: December 2015

Permission to use, copy, modify, distribute, and sell this software and its
documentation for any purpose is hereby granted without fee, provided that
the above copyright notice appear in all copies and that both that
copyright notice and this permission notice appear in supporting
documentation.  No representations are made about the suitability of this
software for any purpose.  It is provided "as is" without express or
implied warranty.
*/

#include "pmparser.h"
#include "log.h"

/**
 * gobal variables
 */
//procmaps_struct* g_last_head=NULL;
//procmaps_struct* g_current=NULL;



procmaps_iterator* pmparser_parse(int pid){
    LOGI("pmparser_parse called with pid: %d", pid);

    procmaps_iterator* maps_it = (procmaps_iterator *)malloc(sizeof(procmaps_iterator));
    if (!maps_it) {
        LOGI("Failed to allocate memory for procmaps_iterator");
        return NULL;
    }
    LOGI("Allocated memory for procmaps_iterator: %p", maps_it);

    char maps_path[500];
    if(pid >= 0 ){
        snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
        LOGI("Constructed maps_path for pid: %s", maps_path);
    } else {
        snprintf(maps_path, sizeof(maps_path), "/proc/self/maps");
        LOGI("Constructed maps_path for self: %s", maps_path);
    }

    FILE* file = fopen(maps_path, "r");
    if(!file){
        LOGI("pmparser: cannot open the memory maps, %s", strerror(errno));
        free(maps_it);
        return NULL;
    }
    LOGI("Opened maps file: %s", maps_path);

    int ind = 0;
    char buf[PROCMAPS_LINE_MAX_LENGTH];
    procmaps_struct* list_maps = NULL;
    procmaps_struct* tmp;
    procmaps_struct* current_node = NULL;
    char addr1[20], addr2[20], perm[8], offset[20], dev[10], inode[30], pathname[PATH_MAX];

    while (fgets(buf, PROCMAPS_LINE_MAX_LENGTH, file)) {
        LOGI("Read line %d: %s", ind + 1, buf);

        // 分配一个新的节点
        tmp = (procmaps_struct*)malloc(sizeof(procmaps_struct));
        if (!tmp) {
            LOGI("Failed to allocate memory for procmaps_struct at line %d", ind + 1);
            fclose(file);
            // 需要释放已分配的节点，避免内存泄漏
            procmaps_struct* iter = list_maps;
            while (iter) {
                procmaps_struct* next = iter->next;
                free(iter);
                iter = next;
            }
            free(maps_it);
            return NULL;
        }
        LOGI("Allocated memory for procmaps_struct: %p", tmp);

        // 填充节点
        _pmparser_split_line(buf, addr1, addr2, perm, offset, dev, inode, pathname);
        LOGI("Parsed line %d - addr1: %s, addr2: %s, perm: %s, offset: %s, dev: %s, inode: %s, pathname: %s",
             ind + 1, addr1, addr2, perm, offset, dev, inode, pathname);

        // 使用临时变量解析地址
        unsigned long tmp_addr_start_ul, tmp_addr_end_ul;
        if (sscanf(addr1, "%lx", &tmp_addr_start_ul) != 1) {
            LOGI("Failed to parse addr_start at line %d", ind + 1);
            free(tmp);
            continue;
        }
        if (sscanf(addr2, "%lx", &tmp_addr_end_ul) != 1) {
            LOGI("Failed to parse addr_end at line %d", ind + 1);
            free(tmp);
            continue;
        }
        LOGI("Parsed addresses - addr_start: 0x%lx, addr_end: 0x%lx", tmp_addr_start_ul, tmp_addr_end_ul);

        tmp->addr_start = (void*)tmp_addr_start_ul;
        tmp->addr_end = (void*)tmp_addr_end_ul;

        // size
        tmp->length = (unsigned long)((char*)tmp->addr_end - (char*)tmp->addr_start);
        LOGI("Calculated length: %lu", tmp->length);

        // perm
        strncpy(tmp->perm, perm, sizeof(tmp->perm) - 1);
        tmp->perm[sizeof(tmp->perm) - 1] = '\0';
        tmp->is_r = (perm[0] == 'r');
        tmp->is_w = (perm[1] == 'w');
        tmp->is_x = (perm[2] == 'x');
        tmp->is_p = (perm[3] == 'p');
        LOGI("Permissions - is_r: %d, is_w: %d, is_x: %d, is_p: %d", tmp->is_r, tmp->is_w, tmp->is_x, tmp->is_p);

        // offset
        if (sscanf(offset, "%lx", &tmp->offset) != 1) {
            LOGI("Failed to parse offset at line %d", ind + 1);
            free(tmp);
            continue;
        }
        LOGI("Parsed offset: 0x%lx", tmp->offset);

        // device
        strncpy(tmp->dev, dev, sizeof(tmp->dev) - 1);
        tmp->dev[sizeof(tmp->dev) - 1] = '\0';
        LOGI("Device: %s", tmp->dev);

        // inode
        tmp->inode = atoi(inode);
        LOGI("Inode: %d", tmp->inode);

        // pathname
        strncpy(tmp->pathname, pathname, sizeof(tmp->pathname) - 1);
        tmp->pathname[sizeof(tmp->pathname) - 1] = '\0';
        LOGI("Pathname: %s", tmp->pathname);

        tmp->next = NULL;

        // 连接节点到链表
        if(ind == 0){
            list_maps = tmp;
            current_node = list_maps;
            LOGI("Initialized list_maps with first node: %p", list_maps);
        }
        else{
            current_node->next = tmp;
            current_node = tmp;
            LOGI("Appended node to list_maps: %p", tmp);
        }
        ind++;
    }

    if (ferror(file)) {
        LOGI("Error occurred while reading the maps file");
        // 释放已分配的节点和 maps_it
        procmaps_struct* iter = list_maps;
        while (iter) {
            procmaps_struct* next = iter->next;
            free(iter);
            iter = next;
        }
        fclose(file);
        free(maps_it);
        return NULL;
    }

    // 关闭文件
    fclose(file);
    LOGI("Closed maps file: %s", maps_path);

    // 设置迭代器
    maps_it->head = list_maps;
    maps_it->current = list_maps;
    LOGI("Initialized procmaps_iterator - head: %p, current: %p", maps_it->head, maps_it->current);

    return maps_it;
}

procmaps_struct* pmparser_next(procmaps_iterator* p_procmaps_it){
    if(p_procmaps_it->current == NULL)
        return NULL;
    procmaps_struct* p_current = p_procmaps_it->current;
    p_procmaps_it->current = p_procmaps_it->current->next;
    return p_current;
    /*
    if(g_current==NULL){
        g_current=g_last_head;
    }else
        g_current=g_current->next;

    return g_current;
    */
}



void pmparser_free(procmaps_iterator* p_procmaps_it){
    procmaps_struct* maps_list = p_procmaps_it->head;
    if(maps_list==NULL) return ;
    procmaps_struct* act=maps_list;
    procmaps_struct* nxt=act->next;
    while(act!=NULL){
        free(act);
        act=nxt;
        if(nxt!=NULL)
            nxt=nxt->next;
    }
    free(p_procmaps_it);
}


void _pmparser_split_line(
        char*buf,char*addr1,char*addr2,
        char*perm,char* offset,char* device,char*inode,
        char* pathname){
    //
    int orig=0;
    int i=0;
    //addr1
    while(buf[i]!='-'){
        addr1[i-orig]=buf[i];
        i++;
    }
    addr1[i]='\0';
    i++;
    //addr2
    orig=i;
    while(buf[i]!='\t' && buf[i]!=' '){
        addr2[i-orig]=buf[i];
        i++;
    }
    addr2[i-orig]='\0';

    //perm
    while(buf[i]=='\t' || buf[i]==' ')
        i++;
    orig=i;
    while(buf[i]!='\t' && buf[i]!=' '){
        perm[i-orig]=buf[i];
        i++;
    }
    perm[i-orig]='\0';
    //offset
    while(buf[i]=='\t' || buf[i]==' ')
        i++;
    orig=i;
    while(buf[i]!='\t' && buf[i]!=' '){
        offset[i-orig]=buf[i];
        i++;
    }
    offset[i-orig]='\0';
    //dev
    while(buf[i]=='\t' || buf[i]==' ')
        i++;
    orig=i;
    while(buf[i]!='\t' && buf[i]!=' '){
        device[i-orig]=buf[i];
        i++;
    }
    device[i-orig]='\0';
    //inode
    while(buf[i]=='\t' || buf[i]==' ')
        i++;
    orig=i;
    while(buf[i]!='\t' && buf[i]!=' '){
        inode[i-orig]=buf[i];
        i++;
    }
    inode[i-orig]='\0';
    //pathname
    pathname[0]='\0';
    while(buf[i]=='\t' || buf[i]==' ')
        i++;
    orig=i;
    while(buf[i]!='\t' && buf[i]!=' ' && buf[i]!='\n'){
        pathname[i-orig]=buf[i];
        i++;
    }
    pathname[i-orig]='\0';

}

void pmparser_print(procmaps_struct* map, int order){

    procmaps_struct* tmp=map;
    int id=0;
    if(order<0) order=-1;
    while(tmp!=NULL){
        //(unsigned long) tmp->addr_start;
        if(order==id || order==-1){
            printf("Backed by:\t%s\n",strlen(tmp->pathname)==0?"[anonym*]":tmp->pathname);
            printf("Range:\t\t%p-%p\n",tmp->addr_start,tmp->addr_end);
            printf("Length:\t\t%ld\n",tmp->length);
            printf("Offset:\t\t%ld\n",tmp->offset);
            printf("Permissions:\t%s\n",tmp->perm);
            printf("Inode:\t\t%d\n",tmp->inode);
            printf("Device:\t\t%s\n",tmp->dev);
        }
        if(order!=-1 && id>order)
            tmp=NULL;
        else if(order==-1){
            printf("#################################\n");
            tmp=tmp->next;
        }else tmp=tmp->next;

        id++;
    }
}
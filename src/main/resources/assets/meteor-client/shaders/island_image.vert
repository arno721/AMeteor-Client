#version 330 core

layout (location = 0) in vec4 Position;
layout (location = 1) in vec2 Texture;
layout (location = 2) in vec2 Local;
layout (location = 3) in vec4 Box;
layout (location = 4) in vec4 Color;

layout (std140) uniform MeshData {
    mat4 u_Proj;
    mat4 u_ModelView;
};

out vec2 v_TexCoord;
out vec2 v_Local;
out vec4 v_Box;
out vec4 v_Color;

void main() {
    gl_Position = u_Proj * u_ModelView * Position;

    v_TexCoord = Texture;
    v_Local = Local;
    v_Box = Box;
    v_Color = Color;
}
